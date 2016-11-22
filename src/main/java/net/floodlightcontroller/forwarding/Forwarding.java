/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.forwarding;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.core.annotations.LogMessageCategory;
import net.floodlightcontroller.core.annotations.LogMessageDoc;
import net.floodlightcontroller.core.annotations.LogMessageDocs;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.ExpiringMap;
import net.floodlightcontroller.util.MptcpConnection;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.LoadingCache;
import net.floodlightcontroller.dropmeter.DropMeter;


@LogMessageCategory("Flow Programming")
public class Forwarding extends ForwardingBase implements IFloodlightModule {
	protected static Logger log = LoggerFactory.getLogger(Forwarding.class);
	//this is used to avoid the assignment of the same subflow setup packet to different threads in the FM
	//ensures that the path assignment is not changed for each subflow in the flows hashtable
	ExpiringMap<String, Route> map;
	
	//hashtable that stores the MPTCP connections information
	ExpiringMap<String,MptcpConnection> flows;
	//stores set of paths to avoid calculations from the TM
	ExpiringMap<String,ArrayList<Route>> pathCache;
	//used in random-based approach
	public Random randomGenerator;
	//used to store the pair of primary IPs to avoid the assignment of the shortest path to multiple subflows
	ExpiringMap<String,Integer> primaryIps;
	
	
	
	//if set to false then we have the MPTCP-aware approach
	public final boolean randomForwarding=false;
	// specify the underlying datacenter topology. Used in path calculation
	public final String topo="ft";
	// specify if you want to calculate disjoint paths
	public final boolean isDisjoint=true;
	//specify the number of paths that you want to retrieve for k-shortest and k-edge-disjoint paths
	//if you want only the shortest paths then specify assign k with zero
	public final int k=8;
	

	
	public static byte[] SHAsum(byte[] convertme) throws NoSuchAlgorithmException{
	    MessageDigest md = MessageDigest.getInstance("SHA-1"); 
	    byte [] sha1 = md.digest(convertme);
	    byte [] token = Arrays.copyOfRange(sha1, 0, 4);
	    return token;
	}

	private static String byteArray2Hex(final byte[] hash) {
	    Formatter formatter = new Formatter();
	    for (byte b : hash) {
	        formatter.format("%02X", b);
	    }
	    return formatter.toString();
	}
	
	@Override
	@LogMessageDoc(level="ERROR",
	message="Unexpected decision made for this packet-in={}",
	explanation="An unsupported PacketIn decision has been " +
			"passed to the flow programming component",
			recommendation=LogMessageDoc.REPORT_CONTROLLER_BUG)
	public  Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		// We found a routing decision (i.e. Firewall is enabled... it's the only thing that makes RoutingDecisions)
		if (decision != null) {
			log.info("decision!=null");
			if (log.isTraceEnabled()) {
				log.trace("Forwaring decision={} was made for PacketIn={}", decision.getRoutingAction().toString(), pi);
			}

			switch(decision.getRoutingAction()) {
			case NONE:
				// don't do anything
				return Command.CONTINUE;
			case FORWARD_OR_FLOOD:
			case FORWARD:
				doForwardFlow(sw, pi, cntx, false);
				return Command.CONTINUE;
			case MULTICAST:
				// treat as broadcast
				doFlood(sw, pi, cntx);
				return Command.CONTINUE;
			case DROP:
				doDropFlow(sw, pi, decision, cntx);
				return Command.CONTINUE;
			default:
				log.error("Unexpected decision made for this packet-in={}", pi, decision.getRoutingAction());
				return Command.CONTINUE;
			}
		} else { // No routing decision was found. Forward to destination or flood if bcast or mcast.
			if (log.isTraceEnabled()) {
				log.trace("No decision was made for PacketIn={}, forwarding", pi);
			}

			if (eth.isBroadcast() || eth.isMulticast()) {
				doFlood(sw, pi, cntx);
			} else {
				doForwardFlow(sw, pi, cntx, false);
			

			}
		}
		
		return Command.CONTINUE;
	}
	
	
	

	@LogMessageDoc(level="ERROR",
			message="Failure writing drop flow mod",
			explanation="An I/O error occured while trying to write a " +
					"drop flow mod to a switch",
					recommendation=LogMessageDoc.CHECK_SWITCH)
	protected void doDropFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		Match m = createMatchFromPacket(sw, inPort, cntx);
		OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd(); // this will be a drop-flow; a flow that will not output to any ports
		List<OFAction> actions = new ArrayList<OFAction>(); // set no action to drop
		U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

		fmb.setCookie(cookie)
		.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
		.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
		.setBufferId(OFBufferId.NO_BUFFER)
		.setMatch(m)
		.setActions(actions) // empty list
		.setPriority(FLOWMOD_DEFAULT_PRIORITY);

		try {
			if (log.isDebugEnabled()) {
				log.debug("write drop flow-mod sw={} match={} flow-mod={}",
						new Object[] { sw, m, fmb.build() });
			}
			boolean dampened = messageDamper.write(sw, fmb.build());
			log.debug("OFMessage dampened: {}", dampened);
		} catch (IOException e) {
			log.error("Failure writing drop flow mod", e);
		}
	}

	protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		// Check if we have the location of the destination
		IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
		if (dstDevice != null) {
			IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
			DatapathId srcIsland = topologyService.getL2DomainId(sw.getId());

			if (srcDevice == null) {
				log.debug("No device entry found for source device");
				return;
			}
			if (srcIsland == null) {
				log.debug("No openflow island found for source {}/{}",
						sw.getId().toString(), inPort);
				return;
			}

			// Validate that we have a destination known on the same island
			// Validate that the source and destination are not on the same switchport
			boolean on_same_island = false;
			boolean on_same_if = false;
			for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
				DatapathId dstSwDpid = dstDap.getSwitchDPID();
				DatapathId dstIsland = topologyService.getL2DomainId(dstSwDpid);
				if ((dstIsland != null) && dstIsland.equals(srcIsland)) {
					on_same_island = true;
					if (sw.getId().equals(dstSwDpid) && inPort.equals(dstDap.getPort())) {
						on_same_if = true;
					}
					break;
				}
			}

			if (!on_same_island) {
				// Flood since we don't know the dst device
				if (log.isTraceEnabled()) {
					log.trace("No first hop island found for destination " +
							"device {}, Action = flooding", dstDevice);
				}
				doFlood(sw, pi, cntx);
				return;
			}

			if (on_same_if) {
				if (log.isTraceEnabled()) {
					log.trace("Both source and destination are on the same " +
							"switch/port {}/{}, Action = NOP",
							sw.toString(), inPort);
				}
				return;
			}

			// Install all the routes where both src and dst have attachment
			// points.  Since the lists are stored in sorted order we can
			// traverse the attachment points in O(m+n) time
			SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
			Arrays.sort(srcDaps, clusterIdComparator);
			SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
			Arrays.sort(dstDaps, clusterIdComparator);

			int iSrcDaps = 0, iDstDaps = 0;

			while ((iSrcDaps < srcDaps.length) && (iDstDaps < dstDaps.length)) {
				SwitchPort srcDap = srcDaps[iSrcDaps];
				SwitchPort dstDap = dstDaps[iDstDaps];

				// srcCluster and dstCluster here cannot be null as
				// every switch will be at least in its own L2 domain.
				DatapathId srcCluster = topologyService.getL2DomainId(srcDap.getSwitchDPID());
				DatapathId dstCluster = topologyService.getL2DomainId(dstDap.getSwitchDPID());

				int srcVsDest = srcCluster.compareTo(dstCluster);
				if (srcVsDest == 0) {
					if (!srcDap.equals(dstDap)) {
						Route route =
								routingEngineService.getRoute(srcDap.getSwitchDPID(), 
										srcDap.getPort(),
										dstDap.getSwitchDPID(),
										dstDap.getPort(), U64.of(0)); //cookie = 0, i.e., default route
						//log.info("Default route info===" + route.toString());
						
						if (route != null) {
							if (log.isTraceEnabled()) {
								log.trace("pushRoute inPort={} route={} " +
										"destination={}:{}",
										new Object[] { inPort, route,
										dstDap.getSwitchDPID(),
										dstDap.getPort()});
							}

							U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

							Match m = createMatchFromPacket(sw, inPort, cntx);
							
							
							TCP tcp2 = null;
							Ethernet eth2 = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
							if (eth2.getEtherType() == EthType.IPv4) {
							IPv4 ip2 = (IPv4) eth2.getPayload();
							if (ip2.getProtocol().equals(IpProtocol.TCP)) {
							IPv4Address srcIp2 = ip2.getSourceAddress();
							IPv4Address dstIp2 = ip2.getDestinationAddress();
							tcp2 = (TCP) ip2.getPayload();
							
							//retrieve the source and destination port numbers
							TransportPort srcPort = tcp2.getSourcePort();
							TransportPort dstPort = tcp2.getDestinationPort();
							
							log.info("SrcPort is *******");
							log.info(srcPort.toString());
							String ports= srcIp2.toString()+"-"+String.valueOf(srcPort.getPort()) + "-"+ dstIp2.toString() +String.valueOf(dstPort.getPort()) ;
							if(!map.containsKey(ports)){
							
							//U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
							if(randomForwarding){

								//log.info(srcIp2.toString() + " " + dstIp2.toString() );
								
								//check if we already stored the routes to the cache
								ArrayList<Route> v = new ArrayList<Route>();
								if(pathCache.containsKey(srcIp2+"-"+dstIp2)){
									v=pathCache.get(srcIp2+"-"+dstIp2);
									//log.info("Retrieved routes from cache");
								}
								else{
								v=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false,k,isDisjoint,topo);
								//log.info("Printing routes =" + v.size() );
								for(Route r : v){
									r = addFirstLastNodePortTuple(r, srcDap.getSwitchDPID(), dstDap.getSwitchDPID(),srcDap.getPort(),dstDap.getPort());	
									//log.info(r.toString());
								}
								pathCache.put(srcIp2+"-"+dstIp2, v);
								//log.info("Retrieved routes from routingEngine");
								}
						
							    
							    //randomly choose a path --> Simulating ECMP behavior
								int index = randomGenerator.nextInt(v.size());
								//log.info( " Index=" + index + "Selected ecmp route= " + v.get(index).toString());
								log.info("Line 316: RANDOM FORWARDING");
								log.info(v.get(index).getPath().toString());
								pushRoute(v.get(index), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
								pushReverseRoute(v.get(index), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD,true);
								
								map.put(ports, v.get(index));
								
								} //end of if ecmp enabled
								else{
							
							try{
							byte [] options;
							options= tcp2.getOptions();
							//log.info(ports + " Options Length=" + Integer.toString(options.length) );
							if(options.length>=32){
							//log.info("Flags= " + String.valueOf(tcp2.getFlags()) + srcIp2.toString() + " " + dstIp2.toString() + " " + " srcport= " + String.valueOf(srcPort.getPort()) + " dstport= "+ String.valueOf(dstPort.getPort())  +" Kind Options=" + tcp2.isMPTCPEnabled() + " Subtype=" + tcp2.getMptcpSubtype());
							/*
							 * if we received a packet with MP_CAPABLE option
							 * Either we create a new MPTCP connection or we have the SYN+ACK packet and we should calculate the token
							 */
								/*log.info("Printing active hasmap keys");
								 for(String key : active.keySet()){
									 log.info("Key=" + key);
								 }*/
							if(tcp2.getMptcpSubtype()==0){
							
								primaryIps.put(srcIp2+"-"+dstIp2, 0);
								//check if we already stored the routes to the cache
								ArrayList<Route> v = new ArrayList<Route>();
								if(pathCache.containsKey(srcIp2+"-"+dstIp2)){
									v=pathCache.get(srcIp2+"-"+dstIp2);
									//log.info("Retrieved routes from cache");
								}
								else{
								v=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false,k,isDisjoint,topo);
								//log.info("Printing routes = " + v.size());
								for(Route r : v){
									r = addFirstLastNodePortTuple(r, srcDap.getSwitchDPID(), dstDap.getSwitchDPID(),srcDap.getPort(),dstDap.getPort());	
									//log.info(r.toString());
								}
								pathCache.put(srcIp2+"-"+dstIp2, v);
								
								}

								log.info("Line 408: CAPABLE");
								log.info(v.get(0).getPath().toString());
								log.info("Switch ID");
						    	log.info(sw.getId().toString());
						    	log.info("Port ID");
						    	log.info(v.get(0).getPath().get(1).getPortId().toString());
								
								DropMeter dm = new DropMeter();
							    IOFSwitch  mySwitch0 = switchService.getSwitch((v.get(0).getPath().get(1).getNodeId()));
								dm.createMeter(mySwitch0);
								log.info("Line 420 Switch ID");
								log.info(mySwitch0.getId().toString());
					            dm.bindMeterWithFlow(inPort, dstPort, srcIp2, mySwitch0, srcPort, v.get(0));
					            

								pushRoute(v.get(0), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
								//log.info("Selected route size= " + v.get(0).getPath().size());
								//log.info("SelectedRoute=" + v.get(0).toString());
								pushReverseRoute(v.get(0), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD,true);
								map.put(ports, v.get(0));
		
								
							}
							/*
							 * if the packet contains an MP_JOIN Option then we need to do the following
							 * 	1. Retrieve the token from the packet
							 * 	2. Find the MptcpConnection from the flows Hashmap
							 *  3. Get the route from the MptcpConnection class (according to IP addresses)
							 * 	4. Push the route
							 *  5. Push the reverse route
							 */
						
							else if(tcp2.getMptcpSubtype()==1){
						
								byte [] token = tcp2.getMptcpToken();
								
								MptcpConnection c = flows.get(byteArray2Hex(token));
								if(c==null){
									//log.info("Connection not found!!!!");
									log.info("new connection using token {}",byteArray2Hex(token));
									MptcpConnection newConnection = new MptcpConnection(srcIp2,dstIp2,srcPort.getPort(),dstPort.getPort(),token);
									flows.put(byteArray2Hex(token), newConnection);
									
									//we need to find all the paths between the IPs and store them to the mptcpConnection object
									ArrayList<Route> v2 = new ArrayList<Route>();
									
									
								    //v2=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false);
								    if(pathCache.containsKey(srcIp2+"-"+dstIp2)){
										v2=pathCache.get(srcIp2+"-"+dstIp2);
										//log.info("Retrieved routes from cache(mptcp-aware)");
									}
									else{
									v2=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false,k,isDisjoint,topo);
									//log.info("Printing ROutes");
									for(Route r : v2){
										r = addFirstLastNodePortTuple(r, srcDap.getSwitchDPID(), dstDap.getSwitchDPID(),srcDap.getPort(),dstDap.getPort());	
										//log.info(r.toString());
									}
									pathCache.put(srcIp2+"-"+dstIp2, v2);
									//log.info("Retrieved routes from routingEngine. Route count =" + v2.size());
									}
								    int subNum=0;
								   if(primaryIps.containsKey(srcIp2+"-"+dstIp2)){
									   subNum=1;
								   }
								    newConnection.addRoutes(srcIp2, dstIp2, v2,subNum);
								    Route route2 = newConnection.getNextRoute(srcIp2, dstIp2);
								    //log.info("Selected Route (MPJOIN) = " + route2.toString());
									


						            // dm.bindMeterWithFlow(sw, srcPort, route2);
						            IOFSwitch  mySwitch = switchService.getSwitch((route2.getPath().get(1).getNodeId()));
						            log.info("Line 469: JOIN");
									log.info(route2.getPath().toString());

							    	log.info("Switch ID");
							    	log.info(mySwitch.getId().toString());
							    	log.info(sw.getId().toString());
							    	log.info("Port ID");
							    	log.info(route2.getPath().get(1).getPortId().toString());

									DropMeter dm = new DropMeter();
									dm.createMeter(mySwitch);
									log.info("Line 499 Switch ID");
									log.info(mySwitch.getId().toString());
						            dm.bindMeterWithFlow(inPort, dstPort, srcIp2, mySwitch, srcPort, route2);
						            

									pushRoute(route2, m, pi, sw.getId(), cookie,
											cntx, requestFlowRemovedNotifn, false,
											OFFlowModCommand.ADD);		
									pushReverseRoute(route2, m, pi, sw.getId(), cookie,
											cntx, requestFlowRemovedNotifn, false,
											OFFlowModCommand.ADD, true);
									//log.info("Selected route size= " + route2.getPath().size());
									map.put(ports, route2);
									
									}
								else{
									//log.info("Connection retrieved successfully");
									log.info("exist pair using token {}",byteArray2Hex(token));
								if(c.ipsAlreadySeen(srcIp2, dstIp2)){
									//log.info("Already seen those ips");
									
									Route newRoute =c.getNextRoute(srcIp2, dstIp2);
								   //log.info("Selected Route for MP_JOIN -- Connection Retrieved Successfully = " + newRoute.toString());
									//log.info("Selected route size= " + newRoute.getPath().size());


									




									IOFSwitch  mySwitch1 = switchService.getSwitch((newRoute.getPath().get(1).getNodeId()));

									log.info("Line 493: JOIN2");
									log.info(newRoute.getPath().toString());

							    	log.info("Switch ID");
							    	log.info(mySwitch1.getId().toString());
							    	log.info(sw.getId().toString());
							    	log.info("Port ID");
							    	log.info(newRoute.getPath().get(1).getPortId().toString());

									DropMeter dm = new DropMeter();
									dm.createMeter(mySwitch1);
									log.info("Line 543 Switch ID");
									log.info(mySwitch1.getId().toString());
						            dm.bindMeterWithFlow(inPort, dstPort, srcIp2, mySwitch1, srcPort, newRoute);
						            


									pushRoute(newRoute, m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
									pushReverseRoute(newRoute, m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD, true);
									map.put(ports, newRoute);
								}
								else{
									//log.info("Not seen those ips");
									ArrayList<Route> v2 = new ArrayList<Route>();
									
									
								    if(pathCache.containsKey(srcIp2+"-"+dstIp2)){
										v2=pathCache.get(srcIp2+"-"+dstIp2);
									}
									else{
									v2=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false,k,isDisjoint,topo);
									for(Route r : v2){
										r = addFirstLastNodePortTuple(r, srcDap.getSwitchDPID(), dstDap.getSwitchDPID(),srcDap.getPort(),dstDap.getPort());	
									}
									pathCache.put(srcIp2+"-"+dstIp2, v2);
									}
								    int subNum=0;
									   if(primaryIps.containsKey(srcIp2+"-"+dstIp2)){
										   subNum=1;
									   }
								    c.addRoutes(srcIp2, dstIp2, v2,subNum);
									Route newRoute =c.getNextRoute(srcIp2, dstIp2);
									   //log.info("Selected Route for MP_JOIN -- Connection Retrieved Successfully = " + newRoute.toString());
									//log.info("Selected route size= " + newRoute.getPath().size());

									

										IOFSwitch  mySwitch2 = switchService.getSwitch((newRoute.getPath().get(1).getNodeId()));

										log.info("Line 529: JOIN3");
										log.info(newRoute.getPath().toString());

								    	log.info("Switch ID");
								    	log.info(mySwitch2.getId().toString());
								    	log.info(sw.getId().toString());
								    	log.info("Port ID");
								    	log.info(newRoute.getPath().get(1).getPortId().toString());

										DropMeter dm = new DropMeter();
										dm.createMeter(mySwitch2);
										log.info("Line 596 Switch ID");
										log.info(mySwitch2.getId().toString());
							            dm.bindMeterWithFlow(inPort, dstPort, srcIp2, mySwitch2, srcPort, newRoute);
							            


										pushRoute(newRoute, m, pi, sw.getId(), cookie,
											cntx, requestFlowRemovedNotifn, false,
											OFFlowModCommand.ADD);
										pushReverseRoute(newRoute, m, pi, sw.getId(), cookie,
											cntx, requestFlowRemovedNotifn, false,
											OFFlowModCommand.ADD, true);
										map.put(ports, newRoute);
								}
								}	
							}
							//we have a different subtype and we don't know what to do
							else{
								ArrayList<Route> v = new ArrayList<Route>();
								if(pathCache.containsKey(srcIp2+"-"+dstIp2)){
									v=pathCache.get(srcIp2+"-"+dstIp2);
								}
								else{
								v=routingEngineService.getRoutes(srcDap.getSwitchDPID(),dstDap.getSwitchDPID(),false,k,isDisjoint,"ft");
								for(Route r : v){
									r = addFirstLastNodePortTuple(r, srcDap.getSwitchDPID(), dstDap.getSwitchDPID(),srcDap.getPort(),dstDap.getPort());	
								}
								pathCache.put(srcIp2+"-"+dstIp2, v);
								}
							    //randomly choose a path --> Simulating ECMP behavior
								int index = randomGenerator.nextInt(v.size());

										log.info("Line 560: UNKNOWN SUBTYPE");
										log.info(v.get(index).getPath().toString());


								pushRoute(v.get(index), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
								
							}
							}
							}
							catch(Exception e){

							}
							}
							}else{


										log.info("Line 578: FIND IN MP");
										log.info(map.get(ports).getPath().toString());



								pushRoute(map.get(ports), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
								pushReverseRoute(map.get(ports), m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD,true);
							
							
							}
							
							}
							else{


										log.info("Line 597: NOT  TCP");
										log.info(route.getPath().toString());



							pushRoute(route, m, pi, sw.getId(), cookie,
									cntx, requestFlowRemovedNotifn, false,
									OFFlowModCommand.ADD);

							}
						}
							else{									
										log.info("Line 609: NOT IPV4");
										log.info(route.getPath().toString());





								pushRoute(route, m, pi, sw.getId(), cookie,
										cntx, requestFlowRemovedNotifn, false,
										OFFlowModCommand.ADD);
							}
					}
					iSrcDaps++;
					iDstDaps++;
				} else if (srcVsDest < 0) {
					iSrcDaps++;
				} else {
					iDstDaps++;
				}
			}
			}
		} else {
			// Flood since we don't know the dst device
			doFlood(sw, pi, cntx);
		}
		
	}
	
	public Route addFirstLastNodePortTuple(Route route, DatapathId srcDpid, DatapathId dstDpid, OFPort srcPort, OFPort dstPort){
		List<NodePortTuple> temp =route.getPath();
		temp.add(0, new NodePortTuple(srcDpid,srcPort));
		temp.add(new NodePortTuple(dstDpid,dstPort));
		route.setPath(temp);
		return route;
		
	}

	/**
	 * Instead of using the Firewall's routing decision Match, which might be as general
	 * as "in_port" and inadvertently Match packets erroneously, construct a more
	 * specific Match based on the deserialized OFPacketIn's payload, which has been 
	 * placed in the FloodlightContext already by the Controller.
	 * 
	 * @param sw, the switch on which the packet was received
	 * @param inPort, the ingress switch port on which the packet was received
	 * @param cntx, the current context which contains the deserialized packet
	 * @return a composed Match object based on the provided information
	 */
	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, FloodlightContext cntx) {
		// The packet in match will only contain the port number.
		// We need to add in specifics for the hosts we're routing between.
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort);

		if (FLOWMOD_DEFAULT_MATCH_MAC) {
			mb.setExact(MatchField.ETH_SRC, srcMac)
			.setExact(MatchField.ETH_DST, dstMac);
		}

		if (FLOWMOD_DEFAULT_MATCH_VLAN) {
			if (!vlan.equals(VlanVid.ZERO)) {
				mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
			}
		}

		// TODO Detect switch type and match to create hardware-implemented flow
		// TODO Allow for IPv6 matches
		if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
			IPv4 ip = (IPv4) eth.getPayload();
			IPv4Address srcIp = ip.getSourceAddress();
			IPv4Address dstIp = ip.getDestinationAddress();
			
			if (FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
				mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
				.setExact(MatchField.IPV4_SRC, srcIp)
				.setExact(MatchField.IPV4_DST, dstIp);
			}

			if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
				/*
				 * Take care of the ethertype if not included earlier,
				 * since it's a prerequisite for transport ports.
				 */
				if (!FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
					mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
				}
				
				if (ip.getProtocol().equals(IpProtocol.TCP)) {
					TCP tcp = (TCP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
					.setExact(MatchField.TCP_SRC, tcp.getSourcePort())
					.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
				} else if (ip.getProtocol().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
					.setExact(MatchField.UDP_SRC, udp.getSourcePort())
					.setExact(MatchField.UDP_DST, udp.getDestinationPort());
				}
			}
		} else if (eth.getEtherType() == EthType.ARP) { /* shallow check for equality is okay for EthType */
			mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
		}
		return mb.build();
	}

	/**
	 * Creates a OFPacketOut with the OFPacketIn data that is flooded on all ports unless
	 * the port is blocked, in which case the packet will be dropped.
	 * @param sw The switch that receives the OFPacketIn
	 * @param pi The OFPacketIn that came to the switch
	 * @param cntx The FloodlightContext associated with this OFPacketIn
	 */
	@LogMessageDoc(level="ERROR",
			message="Failure writing PacketOut " +
					"switch={switch} packet-in={packet-in} " +
					"packet-out={packet-out}",
					explanation="An I/O error occured while writing a packet " +
							"out message to the switch",
							recommendation=LogMessageDoc.CHECK_SWITCH)
	protected void doFlood(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		if (topologyService.isIncomingBroadcastAllowed(sw.getId(), inPort) == false) {
			if (log.isTraceEnabled()) {
				log.trace("doFlood, drop broadcast packet, pi={}, " +
						"from a blocked port, srcSwitch=[{},{}], linkInfo={}",
						new Object[] {pi, sw.getId(), inPort});
			}
			return;
		}

		// Set Action to flood
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		List<OFAction> actions = new ArrayList<OFAction>();
		if (sw.hasAttribute(IOFSwitch.PROP_SUPPORTS_OFPP_FLOOD)) {
			actions.add(sw.getOFFactory().actions().output(OFPort.FLOOD, Integer.MAX_VALUE)); // FLOOD is a more selective/efficient version of ALL
		} else {
			actions.add(sw.getOFFactory().actions().output(OFPort.ALL, Integer.MAX_VALUE));
		}
		pob.setActions(actions);

		// set buffer-id, in-port and packet-data based on packet-in
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(inPort);
		pob.setData(pi.getData());

		try {
			if (log.isTraceEnabled()) {
				log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
						new Object[] {sw, pi, pob.build()});
			}
			messageDamper.write(sw, pob.build());
		} catch (IOException e) {
			log.error("Failure writing PacketOut switch={} packet-in={} packet-out={}",
					new Object[] {sw, pi, pob.build()}, e);
		}

		return;
	}

	// IFloodlightModule methods

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// We don't export any services
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService>
	getServiceImpls() {
		// We don't have any services
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDeviceService.class);
		l.add(IRoutingService.class);
		l.add(ITopologyService.class);
		l.add(IDebugCounterService.class);
		l.add(ILinkDiscoveryService.class);
		return l;
	}

	@Override
	@LogMessageDocs({
		@LogMessageDoc(level="WARN",
				message="Error parsing flow idle timeout, " +
						"using default of {number} seconds",
						explanation="The properties file contains an invalid " +
								"flow idle timeout",
								recommendation="Correct the idle timeout in the " +
				"properties file."),
				@LogMessageDoc(level="WARN",
				message="Error parsing flow hard timeout, " +
						"using default of {number} seconds",
						explanation="The properties file contains an invalid " +
								"flow hard timeout",
								recommendation="Correct the hard timeout in the " +
						"properties file.")
	})
	public void init(FloodlightModuleContext context) throws FloodlightModuleException, FileNotFoundException {
		super.init();
		this.floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		this.deviceManagerService = context.getServiceImpl(IDeviceService.class);
		this.routingEngineService = context.getServiceImpl(IRoutingService.class);
		this.topologyService = context.getServiceImpl(ITopologyService.class);
		this.debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		this.switchService = context.getServiceImpl(IOFSwitchService.class);
		this.flows = ExpiringMap.builder()
        		.expiration(5, TimeUnit.SECONDS)
        		.build();
		this.pathCache =ExpiringMap.builder()
        		.expiration(60, TimeUnit.MINUTES)
        		.build();
        randomGenerator = new Random();
        map = ExpiringMap.builder()
        		.expiration(60, TimeUnit.SECONDS)
        		.build();
        primaryIps=ExpiringMap.builder()
        		.expiration(1200, TimeUnit.SECONDS)
        		.build();	
       

       
		Map<String, String> configParameters = context.getConfigParams(this);
		String tmp = configParameters.get("hard-timeout");
		if (tmp != null) {
			FLOWMOD_DEFAULT_HARD_TIMEOUT = Integer.parseInt(tmp);
			log.info("Default hard timeout set to {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		} else {
			log.info("Default hard timeout not configured. Using {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		tmp = configParameters.get("idle-timeout");
		if (tmp != null) {
			FLOWMOD_DEFAULT_IDLE_TIMEOUT = Integer.parseInt(tmp);
			log.info("Default idle timeout set to {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		} else {
			log.info("Default idle timeout not configured. Using {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		tmp = configParameters.get("priority");
		if (tmp != null) {
			FLOWMOD_DEFAULT_PRIORITY = Integer.parseInt(tmp);
			log.info("Default priority set to {}.", FLOWMOD_DEFAULT_PRIORITY);
		} else {
			log.info("Default priority not configured. Using {}.", FLOWMOD_DEFAULT_PRIORITY);
		}
		tmp = configParameters.get("set-send-flow-rem-flag");
		if (tmp != null) {
			FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = Boolean.parseBoolean(tmp);
			log.info("Default flags will be set to SEND_FLOW_REM.");
		} else {
			log.info("Default flags will be empty.");
		}
		tmp = configParameters.get("match");
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("vlan") && !tmp.contains("mac") && !tmp.contains("ip") && !tmp.contains("port")) {
				/* leave the default configuration -- blank or invalid 'match' value */
			} else {
				FLOWMOD_DEFAULT_MATCH_VLAN = tmp.contains("vlan") ? true : false;
				FLOWMOD_DEFAULT_MATCH_MAC = tmp.contains("mac") ? true : false;
				FLOWMOD_DEFAULT_MATCH_IP_ADDR = tmp.contains("ip") ? true : false;
				FLOWMOD_DEFAULT_MATCH_TRANSPORT = tmp.contains("port") ? true : false;

			}
		}
		log.info("Default flow matches set to: VLAN=" + FLOWMOD_DEFAULT_MATCH_VLAN
				+ ", MAC=" + FLOWMOD_DEFAULT_MATCH_MAC
				+ ", IP=" + FLOWMOD_DEFAULT_MATCH_IP_ADDR
				+ ", TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT);

	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		super.startUp();
	}
}
