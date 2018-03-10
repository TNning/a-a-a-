package pubg.radar.sniffer

import com.badlogic.gdx.math.Vector2
import org.pcap4j.core.BpfProgram.BpfCompileMode.OPTIMIZE
import org.pcap4j.core.NotOpenException
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode.PROMISCUOUS
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.*
import pubg.radar.*
import pubg.radar.deserializer.proc_raw_packet
import pubg.radar.sniffer.SniffOption.PPTPFilter
import pubg.radar.sniffer.SniffOption.PortFilter
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.experimental.and

const val check1 = 12
const val check2 = 8
const val check3 = 4
const val flag1: Byte = 0
const val flag2: Byte = -1
fun Byte.check() = this == flag1 || this == flag2
class DevDesc(val dev: PcapNetworkInterface, val address: Inet4Address) {
    override fun toString(): String {
        return "[${address.hostAddress}] - ${dev.description}"
    }
}

enum class SniffOption {
    PortFilter,
    PPTPFilter
}

class Sniffer {
    companion object : GameListener {

        override fun onGameOver() {
        }

        private val nif: PcapNetworkInterface
        val targetAddr: Inet4Address
        val sniffOption: SniffOption

        val preSelfCoords = Vector2()
        var preDirection = Vector2()
        var selfCoords = Vector2()

    init {
      register(this)
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
      } catch (e: Exception) {
        e.printStackTrace()
      }
      
      var nif: PcapNetworkInterface? = null
      var sniffOption: SniffOption? = null
      val devs = Pcaps.findAllDevs()
      val prop = Properties()
      var found = false
      try {
        FileInputStream(settingFile).use {
          prop.load(it)
          val dev_desc = prop.getProperty(PROP_NetworkInterface)
          sniffOption = SniffOption.valueOf(prop.getProperty(PROP_SniffOption))
          nif = devs.first { it.description == dev_desc }
        }
        found = true
      } catch (e: Exception) {
        found = false
      }
      
      val choices = ArrayList<DevDesc>()
      for (dev in devs)
        dev.addresses
            .filter { it.address is Inet4Address }
            .mapTo(choices) { DevDesc(dev, it.address as Inet4Address) }
      if (choices.isEmpty()) {
        notify("No ipv4 network interface!!")
        System.exit(-1)
      }
      val arrayChoices = choices.toTypedArray()
      val msg = "Choose the network interface to sniff..."
      val group = ButtonGroup()
      val portFilter = JRadioButton("Direct network connection/virtual network adapter)",
                                    !found || sniffOption == PortFilter)
      val routeIpFilter = JRadioButton("PPTP tunneling",
                                       found && sniffOption == PPTPFilter)
      group.add(portFilter)
      group.add(routeIpFilter)
      val netDevs = JComboBox(arrayChoices)
      if (found) netDevs.selectedItem = arrayChoices.firstOrNull { it.dev === nif }
      val params = arrayOf(msg, netDevs, portFilter, routeIpFilter)
      
      val option = showConfirmDialog(null, params, "Network interfaces", OK_CANCEL_OPTION)
      if (option == CANCEL_OPTION)
        System.exit(0)
      
      val choice = netDevs.selectedIndex
      if (choice == -1)
        System.exit(-1)
      
      val devDesc = arrayChoices[choice]
      nif = devDesc.dev
      val localAddr = devDesc.address
      
      when {
        portFilter.isSelected -> sniffOption = PortFilter
        routeIpFilter.isSelected -> sniffOption = PPTPFilter
      }
      
      try {
        File(settingHome).mkdirs()
        FileOutputStream(settingFile).use {
          if (devDesc.dev.description == null) return@use
          prop.setProperty(PROP_NetworkInterface, devDesc.dev.description)
          prop.setProperty(PROP_SniffOption, sniffOption!!.name)
          prop.store(it, "network interface to sniff")
        }
      } catch (e: Exception) {
        notify("Cannot save setting file to $settingFile")
      }
      
      this.nif = nif!!
      this.localAddr = localAddr
      this.sniffOption = sniffOption!!
    }
    
    const val snapLen = 65536
    val mode = PROMISCUOUS
    const val timeout = 1
    
    const val PPTPFlag: Byte = 0b0011_0000
    const val ACKFlag: Byte = 0b1000_0000.toByte()
    
    fun ByteArray.toIntBE(pos: Int, num: Int): Int {
      var value = 0
      for (i in 0 until num)
        value = value or ((this[pos + num - 1 - i].toInt() and 0xff) shl 8 * i)
      return value
    }
    
    fun parsePPTPGRE(raw: ByteArray): Packet? {
      var i = 0
      if (raw[i] != PPTPFlag) return null//PPTP
      i++
      val hasAck = (raw[i] and ACKFlag) != 0.toByte()
      i++
      val protocolType = raw.toIntBE(i, 2)
      i += 2
      if (protocolType != 0x880b) return null
      val payloadLength = raw.toIntBE(i, 2)
      i += 2
      val callID = raw.toIntBE(i, 2)
      i += 2
      val seq = raw.toIntBE(i, 4)
      i += 4
      if (hasAck) {
        val ack = raw.toIntBE(i, 4)
        i += 4
      }
      if (raw[i] != 0x21.toByte()) return null//not ipv4
      i--
      raw[i] = 0
      val pppPkt = PppSelector.newPacket(raw, i, raw.size - i)
      return pppPkt.payload
    }
    
    fun udp_payload(packet: Packet): UdpPacket? {
      return when (sniffOption) {
        PortFilter -> packet
        PPTPFilter -> parsePPTPGRE(packet[IpV4Packet::class.java].payload.rawData)
        
      }?.get(UdpPacket::class.java)
    }
    
    fun sniffLocationOnline() {
      val handle = nif.openLive(snapLen, mode, timeout)
      val filter = when (sniffOption) {
        PortFilter -> "udp portrange 7000-7999"
        PPTPFilter -> "ip[9]=47"
      }
      handle.setFilter(filter, OPTIMIZE)
      thread(isDaemon = true) {
        handle.loop(-1) { packet: Packet? ->
          try {
            packet!!
            val ip = packet[IpPacket::class.java]
            val udp = udp_payload(packet) ?: return@loop
            val raw = udp.payload.rawData
            if (ip.header.srcAddr == localAddr) {
              if (udp.header.dstPort.valueAsInt() in 7000..7999)
                proc_raw_packet(raw, false)
            } else if (udp.header.srcPort.valueAsInt() in 7000..7999)
              proc_raw_packet(raw, true)
          } catch (e: Exception) {
          }
        }
      }
    }
    
    fun sniffLocationOffline(): Thread {
      return thread(isDaemon = true) {
        val files = arrayOf("d:\\new05.pcap")
        for (file in files) {
          val handle = Pcaps.openOffline(file)
          
          while (true) {
            try {
              val packet = handle.nextPacket ?: break
              val ip = packet[IpPacket::class.java]
              val udp = udp_payload(packet) ?: continue
              val raw = udp.payload.rawData
              if (ip.header.srcAddr == localAddr) {
                if (udp.header.dstPort.valueAsInt() in 7000..7999)
                  proc_raw_packet(raw, false)
              } else if (udp.header.srcPort.valueAsInt() in 7000..7999)
                proc_raw_packet(raw,true)
            } catch (e: Exception) {
            }
            Thread.sleep(1)
          }
        }
      }
    }
  }
}
