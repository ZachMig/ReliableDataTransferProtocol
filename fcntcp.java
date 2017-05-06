/**
 * @Author Zachary Migliorini zmm2962
 */

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Class which implements reliable data transfer, TCP-style wrapped around UDP datagrams.
 */
public class fcntcp {
	
	final static ByteOrder be = ByteOrder.BIG_ENDIAN;
	final static int size = 1000;
	
	static ByteBuffer file;
    static byte[] fileArr;
	static SocketAddress addr;
	static Window window;
	
	static DatagramSocket socket;
	
	public static void main(String[] args) { 
		
		boolean client = false, verbose = false;
		String path= "", serverAddress = "";
		long timeout = 1000;
		int port = -1;
		
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			
			case "--client":
			case "-c":
				client = true;
				break;
				
			case "--server":
			case "-s":
				client = false;
				break;
				
			case "--file":
			case "-f":
				path = args[++i];
				break;
				
			case "--timeout":
			case "-t":
				timeout = Long.parseLong(args[++i]);
				break;
				
			case "--verbose":
			case "-v":
				verbose = true;
			
			default:
				if (i != args.length-1) 
					serverAddress = args[i];
				else
					port = Integer.parseInt(args[i]);
			}
		}

//Client
		if (client) {
			
			socket = getSocket();
			try {socket.setSoTimeout((int) timeout); }
			catch (SocketException e) { e.printStackTrace(); }
			addr = getAddr(serverAddress, port);
			DatagramPacket packet;
			
			fileArr = readBinary(path);
			
			window = new Window(2,1);

			byte[] checksumArr;
			
			//Send syn
			byte[] synHeader = new Header(0, 0, window.getRwnd(), (short)0, false, true, false).toByteArray();
			checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(synHeader)).array();
			synHeader[16] = checksumArr[0];
			synHeader[17] = checksumArr[1];
			packet = new DatagramPacket(synHeader, 20, addr);
			try { socket.send(packet);
			} catch (IOException e) { e.printStackTrace(); }
			
			packet.setData(new byte[20]);
			try { socket.receive(packet); }
			catch (IOException e) { e.printStackTrace(); }
	
			byte[] ackHeader = new Header(1, 1, window.getRwnd(), (short)0, true, false, false).toByteArray();
			checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(ackHeader)).array();
			ackHeader[16] = checksumArr[0];
			ackHeader[17] = checksumArr[1];
			packet = new DatagramPacket(ackHeader, 20, addr);
			try { socket.send(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			//Get ready to receive acks
			new ClientReceiveThread(socket, verbose).start();
			
			int offset;
			while(true) { 
				
				if (window.canSendMore()) { 
					
					offset = window.getSendBase();
					
					if (offset >= fileArr.length) {
						synchronized (window) {
							window.notify();
							try { window.wait(); }
							catch (InterruptedException e) { e.printStackTrace(); }
						}
						break;
					}
					
					byte[] segment = Arrays.copyOfRange(fileArr, offset, (fileArr.length-offset < size ? fileArr.length : offset+size));
					byte[] header = new Header(window.getSeq(), window.getRecvAck(), window.getRwnd(), (short) 0, false, false, false).toByteArray();
					byte[] checksum = ByteBuffer.allocate(2).putShort(getDataChecksum(header, segment)).array();
					
					header[16] = checksum[0];
					header[17] = checksum[1];
					
					byte[] data = ByteBuffer.allocate(header.length + segment.length).put(header).put(segment).array();
					
					packet = new DatagramPacket(data, data.length, addr);
					
					if (verbose) 
						System.out.println("Sending: Seq #" + window.getSeq() + " Ack #" + window.getRecvAck() + " - # Bytes In Flight = " + window.getNumUnacked() + " out of window size " + window.getCwnd() + ". " + (fileArr.length - offset) + " bytes left in file.");
					
					try { socket.send(packet); }
					catch (IOException e) { e.printStackTrace(); }
					
					window.incSeq(segment.length);
					window.incRecvAck(1);
					window.incNumUnacked((short) segment.length);
					window.incSendBase(segment.length);

				} else {
					synchronized (window) {
						window.notify();
						try { window.wait(); }
						catch (InterruptedException e) { e.printStackTrace(); }
					}
				}
			}
			
			printDigest(fileArr);
			
			
			//Send Fin
			byte[] finHeader = new Header(window.getSeq(), window.getRecvAck(), window.getRwnd(), (short)0, false, false, true).toByteArray();
			byte[] checksum = ByteBuffer.allocate(2).putShort(getHeaderChecksum(finHeader)).array();
			finHeader[16] = checksum[0];
			finHeader[17] = checksum[1];
			
			
			packet = new DatagramPacket(finHeader, 20, addr);
			try { socket.send(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			//Get Ack
			packet.setData(new byte[20]);
			try { socket.receive(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			//Get fin
			packet.setData(new byte[20]);
			try { socket.receive(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			//Send Ack
			window.incSeq(1);
			ackHeader = new Header(window.getSeq(), window.getRecvAck(), window.getRwnd(), (short)0, false, false, true).toByteArray();
			checksum = ByteBuffer.allocate(2).putShort(getHeaderChecksum(ackHeader)).array();
			ackHeader[16] = checksum[0];
			ackHeader[17] = checksum[1];
			
			packet = new DatagramPacket(ackHeader, 20, addr);
			try { socket.send(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			
//Server	
		} else { 
			socket = getSocket(port);
			DatagramPacket packet = new DatagramPacket(new byte[20], 20);
			
			//Get Syn
			try { socket.receive(packet); }
			catch (IOException e) { e.printStackTrace(); }
			addr = packet.getSocketAddress();

			window = new Window(1, 2);

			
			//send SynAck
			byte[] synackHeader = new Header(0, 1, window.getRwnd(), (short)0, true, true, false).toByteArray(); 
			byte[] checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(synackHeader)).array();
			synackHeader[16] = checksumArr[0];
			synackHeader[17] = checksumArr[1];
			packet = new DatagramPacket(synackHeader, 20, addr);
			try { socket.send(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			//get Ack
			packet = new DatagramPacket(new byte[20], 20);
			try { socket.receive(packet); }
			catch (IOException e) { e.printStackTrace(); }
			
			if (verbose) 
				System.out.println("Connection established");
			
			int rcvBase = 2;
			int lastAck = 2;
			ArrayList<byte[]> serverFile = new ArrayList<byte[]>();
			for (;;) {
				packet = new DatagramPacket(new byte[size+20], size+20);
				
				try { socket.receive(packet);
				} catch (IOException e) { e.printStackTrace();}
				
				byte[] data = packet.getData();
				Header segmentHeader = new Header(ByteBuffer.wrap(Arrays.copyOfRange(data, 0, 20)));
				byte[] segment = Arrays.copyOfRange(data, 20, packet.getLength());
				byte[] ackHeader = new byte[20];
				
				if (validDataChecksum(data)) {
					
					if (segmentHeader.isFin()) {
						//Start Fin sequence
						break;
					}
					
					//If we have received the expected next packet
					if (segmentHeader.getSeqNum() == rcvBase) {
						//Send next Ack
						ackHeader = new Header(window.getSeq(), rcvBase+segment.length, window.getRwnd(), (short)0, true, false, false).toByteArray(); 
						
						window.incSeq(1);
						rcvBase += segment.length;
						lastAck = rcvBase;
						serverFile.add(segment);
					} else {
						//Out of order packet, send lastAck
						if (verbose) 
							System.out.println("Out of order packet #" + segmentHeader.getSeqNum());
						ackHeader = new Header(window.getSeq(), lastAck, window.getRwnd(), (short)0, true, false, false).toByteArray();
					}

				} else {
					//Invalid checksum, send lastAck
					if (verbose)
						System.out.println("Bit error detected in (probable) Seq #" + segmentHeader.getSeqNum());
					ackHeader = new Header(window.getSeq(), lastAck, window.getRwnd(), (short)0, true, false, false).toByteArray(); 
				}
				
				checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(ackHeader)).array();
				ackHeader[16] = checksumArr[0];
				ackHeader[17] = checksumArr[1];
				
				packet = new DatagramPacket(ackHeader, 20, addr);
				
				try { socket.send(packet); 
				} catch (IOException e) {e.printStackTrace();}
			}
			
			if (verbose) 
				System.out.println("Got Fin message from client, beginning shutdown procedure.\n");
			
			//Start fin sequence here
			
			byte[] ackHeader = new Header(window.getSeq(), ++rcvBase, window.getRwnd(), (short)0, true, false, false).toByteArray();
			checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(ackHeader)).array();
			ackHeader[16] = checksumArr[0];
			ackHeader[17] = checksumArr[1];
			packet = new DatagramPacket(ackHeader, 20, addr);
			
			//Respond to fin with Ack
			try { socket.send(packet);
			} catch (IOException e) { e.printStackTrace();}
			
			window.incSeq(1);
			
			//Send fin
			byte[] finHeader = new Header(window.getSeq(), ++rcvBase, window.getRwnd(), (short)0, false, false, true).toByteArray();
			checksumArr = ByteBuffer.allocate(2).putShort(getHeaderChecksum(finHeader)).array();
			finHeader[16] = checksumArr[0];
			finHeader[17] = checksumArr[1];
			packet = new DatagramPacket(finHeader, 20, addr);
			
			try { socket.send(packet);
			} catch (IOException e) { e.printStackTrace();}
			
			//Get last Ack
			packet = new DatagramPacket(new byte[20], 20);
			try { socket.receive(packet);
			} catch (IOException e) { e.printStackTrace();}
			
			ByteBuffer fileBuf = ByteBuffer.allocate( ((serverFile.size()-1) * size) + serverFile.get(serverFile.size()-1).length);
			for (byte[] bytes : serverFile) {
				fileBuf.put(bytes);
			}
			printDigest(fileBuf.array());
			
		}
		
	}
	
	/**
	 * Thread to handle all the ACK receiving for the client-side
	 */
	static class ClientReceiveThread extends Thread {
		
		private DatagramSocket socket;
		private DatagramPacket packet;
		private boolean verbose;
		private int dupCount;
		
		public ClientReceiveThread(DatagramSocket socket, boolean verbose) {
			this.socket = socket;
			this.verbose = verbose;
			this.dupCount = 0;
		}
		
		@Override
		public void run() {
			boolean timeout = false;
			int rcvBase = 0;
			while (rcvBase < fileArr.length-1) {
				for (;;) {
					
					if (window.allPacketsAcked() && window.getSeq() != 2) {
						break;
					}
					
					packet = new DatagramPacket(new byte[20], 20);
					
					try {socket.receive(packet);}
					catch (SocketTimeoutException ste) {
						//On timeout, start sending again from last byte acked
						timeout = true;
						if (verbose) 
							System.out.println("Timeout event at offset " + rcvBase + ". Bytes left in flight = " + window.getNumUnacked());
						window.decSeq(window.getNumUnacked());
						window.decRecvAck(window.getNumUnacked()/size);
						window.setSendBase(rcvBase);
						window.decNumUnacked(window.getNumUnacked());
						break;
					}
					catch (IOException e) {e.printStackTrace();}
					
					Header header = new Header(ByteBuffer.wrap(packet.getData()));

					if (validHeaderChecksum(packet.getData())) {
						
						if (header.getAckNum()-2 > rcvBase) {
							if (verbose) 
								System.out.println("Valid Ack #" + header.getAckNum() + " received.");
							window.decNumUnacked((short) ((header.getAckNum()-2) - rcvBase));
							rcvBase = header.getAckNum()-2;
							window.setSendBase(rcvBase);
						} else if (header.getAckNum()-2 == rcvBase) {
							dupCount++;
							if (verbose) 
								System.out.println("Duplicate Ack #" + header.getAckNum() + " received");
							if (dupCount == 2) {
								//fast retransmit logic
								if (verbose) {
									System.out.println("Beginning fast retransmit");
								}
								window.decSeq(window.getNumUnacked());
								window.decRecvAck(window.getNumUnacked()/size);
								window.setSendBase(rcvBase);
								window.decNumUnacked(window.getNumUnacked());
								break;
							}
						}
						
					} else {
						//Corrupted ack
						if (verbose) 
							System.out.println("Corrupted (probable) Ack #" + header.getAckNum());
						window.decSeq(window.getNumUnacked());
						window.decRecvAck(window.getNumUnacked()/size);
						window.setSendBase(rcvBase);
						window.decNumUnacked(window.getNumUnacked());
						break;
					}
					
				}
				
				
				if (dupCount == 3) {
					window.setSsThresh((short)(window.getCwnd()/2));
					window.setCwnd((short) (window.getSsThresh() + (3*size)));
					dupCount = 0;
				} else if (timeout) {
					window.setSsThresh((short) (window.getCwnd()/2));
					window.setCwnd((short) size);
					timeout = false;
				} else { 
//Congestion control logic
					//Adjust CWND
					if (window.inSlowStart()) { //Maybe move this SS into the for
						//SS Mode
						if (!window.ss) {
							System.out.println("Entering slow-start");
							window.setSs(true);
							window.setCa(false);
						}
						if ((window.getCwnd() * 2) < 32736) 
							window.setCwnd((short) (window.getCwnd() * 2));						
						else 
							window.setCwnd((short) 32736);
					} else {
						//CA Mode
						if (!window.ca) {
							System.out.println("Entering congestion-avoidance");
							window.setSs(false);
							window.setCa(true);
						}
						if ((window.getCwnd() + size) < 32736)
							window.setCwnd((short) (window.getCwnd() + size));
						else 
							window.setCwnd((short) 32736);
					}
				}
				
				if (rcvBase == fileArr.length) {
					synchronized (window) {
						window.notify();
						break;
					}
				}
				
				if (verbose) 
					System.out.println();
				
				synchronized (window) {
					window.notify();
					try {window.wait();}
					catch (InterruptedException e) {e.printStackTrace();}
				}
				
				if (verbose) 
					System.out.println();
			}
		}
	}
		
	/**
	 * Data structure to hold all the relevant info in the added header
	 */
	static class Header {
		
		private int seqNum, ackNum;
		private short rwnd, checksum;
		private boolean ack, syn, fin;
		
		/**
		 * Build a header object from a list of values
		 * @param seqNum the sequence number
		 * @param ackNum the ack number
		 * @param rwnd the receive window
		 * @param checksum the checksum
		 * @param ack is this an ack
		 * @param syn is this a syn
		 * @param fin is this a fin
		 */
		public Header(int seqNum, int ackNum, short rwnd, short checksum, boolean ack, boolean syn, boolean fin) {
			this.seqNum = seqNum;
			this.ackNum = ackNum;
			this.rwnd = rwnd;
			this.checksum = checksum;
			this.ack = ack;
			this.syn = syn;
			this.fin = fin;
		}
		
		/**
		 * Build a header object from a ByteBuffer
		 * @param header 20 byte ByteBuffer
		 */
		public Header(ByteBuffer header) {
			this.seqNum = header.getInt(4);
			
			this.ackNum = header.getInt(8);
			
			byte[] flagsBuf = new byte[] {header.get(13)};
			BitSet bs = BitSet.valueOf(flagsBuf);
			this.fin = bs.get(0);
			this.syn = bs.get(1);
			this.ack = bs.get(4);
			
			this.rwnd = header.getShort(14);
			
			this.checksum = header.getShort(16);
			
		
		}
		
		/**
		 * Builds a byte array of a TCP header according to RFC 793, using the values in this object
		 * @return the 20 byte array
		 */
		public byte[] toByteArray() {
			byte[] ports = new byte[4];
			
			byte[] seqArr = ByteBuffer.allocate(4).putInt(seqNum).array();
			
			byte[] ackArr = ByteBuffer.allocate(4).putInt(ackNum).array();
			
			byte[] flagsArr = getFlagsBytes(ack, syn, fin);
			byte[] rwndArr = ByteBuffer.allocate(2).putShort(rwnd).array();
			
			byte[] checksumArr = ByteBuffer.allocate(2).putShort(checksum).array();
			byte[] urgArr = new byte[2];
			
			
			ByteBuffer header = ByteBuffer.allocate(20);
			header.put(ports);
			header.put(seqArr);
			header.put(ackArr);
			header.put(flagsArr);
			header.put(rwndArr);
			header.put(checksumArr);
			header.put(urgArr);
			
			return header.array();
		}
		
		/**
		 * Build the bytes containing the Ack, Syn, and Fin flags
		 * @param ack is this an ack
		 * @param syn is this a syn
		 * @param fin is this a fin
		 * @return two byte array
		 */
		private byte[] getFlagsBytes(boolean ack, boolean syn, boolean fin) {
			short n = 0;
			if (ack & syn & fin) {
				n = 19;
			} else if (ack && syn && !fin) {
				n = 18;
			} else if (ack && !syn && !fin) {
				n = 16;
			} else if (ack && !syn && fin) {
				n = 17;
			} else if (!ack && syn && fin) {
				n = 3;
			} else if (!ack && !syn && fin) {
				n = 1;
			} else if (!ack && !syn && !fin) {
				n = 0;
			} else if (!ack && syn && !fin) {
				n = 2;
			}
			return ByteBuffer.allocate(2).putShort(n).array();
		}
		
		public int getSeqNum() {
			return seqNum;
		}

		public void setSeqNum(int seqNum) {
			this.seqNum = seqNum;
		}

		public int getAckNum() {
			return ackNum;
		}

		public void setAckNum(int ackNum) {
			this.ackNum = ackNum;
		}

		public short getRwnd() {
			return rwnd;
		}

		public void setRwnd(short rwnd) {
			this.rwnd = rwnd;
		}

		public short getChecksum() {
			return checksum;
		}

		public void setChecksum(short checksum) {
			this.checksum = checksum;
		}

		public boolean isAck() {
			return ack;
		}

		public void setAck(boolean ack) {
			this.ack = ack;
		}

		public boolean isSyn() {
			return syn;
		}

		public void setSyn(boolean syn) {
			this.syn = syn;
		}

		public boolean isFin() {
			return fin;
		}

		public void setFin(boolean fin) {
			this.fin = fin;
		}
		
	}
	
	/**
	 * Synchronized data structure which the client sender and receiver share to communicate and switch control
	 */
	static class Window {
		
		//Dyn array of latest segments/acks recvd by recv thread, then pass control to sender
		
		private boolean ca, ss;
		private int seq, recvAck, sendBase;
		private short rwnd, cwnd, ssthresh, numUnacked;
		
		/**
		 * Standard constructor
		 * @param seq the initial sequence number to be sent
		 * @param recvAck the initial ack number to be sent
		 */
		public Window(int seq, int recvAck) {
			this.ss = false;
			this.ca = false;
			this.seq = seq;
			this.recvAck = recvAck;
			this.ssthresh = 32736;
			this.cwnd = size;
			this.rwnd = 32736;
			this.sendBase = 0;
		}

		public boolean ss() {
			return this.ss;
		}
		
		/**
		 * Checks if we are in slow start
		 */
		public void setSs(boolean val) {
			this.ss = val;
		}
		
		/**
		 * Checks if we are in congestion avoidance
		 */
		public boolean ca() {
			return this.ca;
		}
		
		public void setCa(boolean val) {
			this.ca = val;
		}
		
		public void setSsThresh(short num) {
			this.ssthresh = num;
		}
		
		public short getSsThresh() {
			return this.ssthresh;
		}
		
		/**
		 * Checks if there are remaining packets in flight
		 */
		public synchronized boolean allPacketsAcked() {
			return numUnacked == 0;
		}
		
		public synchronized int getSeq() {
			return this.seq;
		}

		public synchronized void incSeq(int num) {
			this.seq += num;
		}
		
		public synchronized void decSeq(int num) {
			this.seq -= num;
		}
		
		public synchronized int getRecvAck() {
			return this.recvAck;
		}
		
		public synchronized void incRecvAck(int num) {
			this.recvAck += num;
		}
		
		public synchronized void decRecvAck(int num) {
			this.recvAck -= num;
		}
		
		public synchronized short getRwnd() {
			return rwnd;
		}

		public synchronized void setRwnd(short rwnd) {
			this.rwnd = rwnd;
		}

		public synchronized void setCwnd(short cwnd) {
			this.cwnd = cwnd;
		}
		
		public synchronized short getCwnd() {
			return cwnd;
		}

		public synchronized short getNumUnacked() {
			return this.numUnacked;
		}
		
		public synchronized void incNumUnacked(short num) {
			this.numUnacked += num;
		}
		
		public synchronized void decNumUnacked(short num) {
			this.numUnacked -= num;
		}

		public synchronized void setSendBase(int num) {
			this.sendBase = num;
		}
		
		public synchronized int getSendBase() {
			return this.sendBase;
		}
		
		public synchronized void incSendBase(int num) {
			this.sendBase += num;
		}

		/**
		 * Checks if we should be operating according to slow start procedure
		 * @return
		 */
		public synchronized boolean inSlowStart() {
			return cwnd < ssthresh;
		}
		
		/**
		 * Checks if we still have room to send more data this round
		 */
		public synchronized boolean canSendMore() {
			return numUnacked <= Math.min(cwnd-(size), rwnd-(size));
		}
		
	}
	
	/**
	 * Calculates a 16 bit one's complement checksum for a byte array split into two
	 * @param header the array
	 * @Param segment the data segment
	 * @return the 16 bit checksum as a short
	 */
	
	private static short getDataChecksum(byte[] header, byte[] segment) {
		short checksum = 0;
		
		ByteBuffer buf = ByteBuffer.wrap(header);
		for (int i = 0; i < 16; i+=2)
			checksum += buf.getShort(i);
		
		buf = ByteBuffer.wrap(segment);
		for (int i = 0; i < segment.length; i+=2) 
			checksum += buf.getShort(i);
		
		return (short) ~checksum;
	}
	
	/**
	 * Calculates a 16 bit one's complement checksum for a byte array
	 * @param header the array
	 * @return the 16 bit checksum as a short
	 */

	private static short getHeaderChecksum(byte[] header) {
		short checksum = 0;
		ByteBuffer buf = ByteBuffer.wrap(header);
		for (int i = 0; i < 16; i+=2) 
			checksum += buf.getShort(i);
		return (short)~checksum;
	}
	
	/** 
	 * Validates a header plus a block of data
	 * @param data the byte[] to validate
	 * @return t/f if there were no bit errors, or there were, respectively
	 */
	
	private static boolean validDataChecksum(byte[] data) {
		byte[] segment = Arrays.copyOfRange(data, 20, data.length);
		byte[] header = Arrays.copyOfRange(data,  0, 20); 
		short checksum = ByteBuffer.wrap(header).getShort(16);
		header[16] = 0;
		header[17] = 0;
		return (getDataChecksum(header, segment) == checksum);
	}
	
	/**
	 * Tests if a header byte[] is valid for the checksum within
	 * @param header the header
	 * @return whether the header is valid (true) or bit errors occured (false)
	 */
	
	private static boolean validHeaderChecksum(byte[] header) {
		ByteBuffer buf = ByteBuffer.wrap(header);
		short orig = buf.getShort(16);
		buf.putShort(16, (short) 0);
		
		short check = 0;
		for (int i = 0; i < 16; i+=2) {
			check += buf.getShort(i);
		}
		
		return ~check == orig;
	}
	
	/**
	 * Reads in a binary file located at path
	 * @param path the location of the file
	 * @return byte[] of the file
	 */
	
	private static byte[] readBinary(String path) {
		try {
			Path p = Paths.get(path);
			return Files.readAllBytes(p);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} 
	}
	
	/**
	 * Prints a MD5 hash of the file
	 * @param file the file to hash
	 */
	
	private static void printDigest(byte[] file) {
		MessageDigest md;
		byte[] digest = {(byte)-1};
		try {
			md = MessageDigest.getInstance("MD5");
			 digest = md.digest(file);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		System.out.println(javax.xml.bind.DatatypeConverter.printHexBinary(digest));
	}	
	
	/**
	 * Builds a socketAddress
	 * @param serverAddress the server IP
	 * @param port the port
	 * @return the SocketAddress
	 */
	
	private static SocketAddress getAddr(String serverAddress, int port) {
		InetAddress inet;
		SocketAddress addr = null;
		try {
			inet = InetAddress.getByName(serverAddress);
			addr = new InetSocketAddress(inet, port);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		return addr;
	}
	
	/**
	 * Builds a socket with no port
	 * @return the socket
	 */
	
	private static DatagramSocket getSocket() {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return socket;
	}
	
	/**
	 * Builds a socket
	 * @param port the port
	 * @return the socket
	 */
	
	private static DatagramSocket getSocket(int port) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(port);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return socket;
	}
	
}
