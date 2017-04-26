package fcntcp; 
//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS//GET RID OF THIS
//GET RID OF THIS


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;

public class fcntcp {
	
	final static ByteOrder be = ByteOrder.BIG_ENDIAN;
	final static int size = 992;
	
	static ByteBuffer file;
	static SocketAddress addr;
	static Window window;
	
	static DatagramSocket socket;
	
	public static void main(String[] args) throws Exception { //Remove this later
		
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
			addr = getAddr(serverAddress, port);
			DatagramPacket packet;
			
			byte[] fileArr = readBinary(path);
			
			
			//Send syn
			byte[] synHeader = new Header(0, 0, window.getRwnd(), ByteBuffer.wrap(new byte[] {1,1}).getShort(), false, true, false).toByteArray();
			packet = new DatagramPacket(synHeader, 20, addr);
			socket.send(packet);
			
			//Receive synack
			packet.setData(new byte[20]);
			socket.receive(packet);

			window = new Window(2, 1);

			//Get ready to receive acks
			new ClientReceiveThread(socket).start();
			
			//Send ack
			byte[] ackHeader = new Header(1, 1, window.getRwnd(), ByteBuffer.wrap(new byte[] {1,1}).getShort(), true, false, false).toByteArray();
			packet = new DatagramPacket(ackHeader, 20, addr);
			socket.send(packet);
			
			
			//Wait for server to start ReceiveThread
			Thread.sleep(500);
			
			
			for (int offset = 0; offset < fileArr.length; offset+=size) {
				if (window.canSendMore()) {
					byte[] segment = Arrays.copyOfRange(fileArr, offset, (fileArr.length-offset < size ? fileArr.length : offset+size));
					byte[] checksum = getChecksum(segment);
					byte[] header = new Header(window.getSeq(), window.getRecvAck(), window.getRwnd(), ByteBuffer.wrap(checksum).getShort(), false, false, false).toByteArray();
					
					byte[] data = ByteBuffer.allocate(header.length + segment.length).put(header).put(segment).array();
					
					packet = new DatagramPacket(data, data.length, addr);
					socket.send(packet);
					
					window.incSeq(segment.length);
					window.incNumUnacked((short) segment.length);

				} else {
					window.wait();
					
					//Start thread-shared timeout here?
					
					//Adjust CWND, recvThread takes care of RWND
					if (window.inSlowStart()) {
						//SS Mode
						if ((window.getCwnd() * 2) < 32736) 
							window.setCwnd((short) (window.getCwnd() * 2));						
						else 
							window.setCwnd((short) 32736);
					} else {
						//CA Mode
						if ((window.getCwnd() + size) < 32736)
							window.setCwnd((short) (window.getCwnd() + size));
						else 
							window.setCwnd((short) 32736);
					}
				}
			}
			
			
//Server	
		} else { 
			socket = getSocket(port);
			DatagramPacket packet = new DatagramPacket(new byte[20], 20);
			
			//Get syn
			socket.receive(packet);
			addr = packet.getSocketAddress();
			
			//send synack
			byte[] synackHeader = new Header(0, 1, window.getRwnd(), ByteBuffer.wrap(new byte[] {1,1}).getShort(), true, true, false).toByteArray();
			packet = new DatagramPacket(synackHeader, 20, addr);
			socket.send(packet);
			System.out.println("sending packet 2");
			
			//get ack
			packet = new DatagramPacket(new byte[20], 20);
			socket.receive(packet);
			
			window = new Window(1, 2);
					
			//Start receiving data
			new ServerReceiveThread(socket).start();

			
		}
		
	}
	
	static class ClientReceiveThread extends Thread {
		
		private DatagramSocket socket;
		private DatagramPacket packet;
		
		public ClientReceiveThread(DatagramSocket socket) {
			this.socket = socket;
		}
		
		public void run() {
			System.out.println("starting ClientReceiveThread");
		}
	}
	
	static class ServerReceiveThread extends Thread {
		
		private DatagramSocket socket;
		private DatagramPacket packet;
		
		public ServerReceiveThread(DatagramSocket socket) {
			this.socket = socket;
		}
		
		public void run() {
			System.out.println("starting ServerReceiveThread");	
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			int offset = 0;
			for (;;) {
				packet = new DatagramPacket(new byte[size+20], size+20);
				try { socket.receive(packet);
				} catch (IOException e) { e.printStackTrace();}
				
				byte[] data = packet.getData();
				
				if (validChecksum(data)) {
					byte[] segment = Arrays.copyOfRange(data, 20, data.length);
					out.write(segment, offset, segment.length);
					offset += segment.length;
//					byte[] header = new Header(window.getSeq(), window.getRecvAck(), window.getRwnd(), (short)0, true, false, false).toByteArray();
//					packet = new DatagramPacket(header, 20, addr);
//					try { socket.send(packet); 
//					} catch (IOException e) {e.printStackTrace();}

					
				}
			}
			
		}
		
		private boolean validChecksum(byte[] data) {
			byte[] segment = Arrays.copyOfRange(data, 20, data.length);
			Header header = new Header(ByteBuffer.wrap(Arrays.copyOfRange(data, 0, 20)));
			return (ByteBuffer.wrap(getChecksum(segment)).compareTo(ByteBuffer.allocate(2).putShort(header.getChecksum())) == 0 ? true : false);
		}
	}
	
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
	
	private static DatagramSocket getSocket() {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return socket;
	}
	
	private static DatagramSocket getSocket(int port) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(port);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return socket;
	}
	
	private static byte[] getChecksum(byte[] segment) {
		short checksumShort = 0;
		for (int offset = segment.length; offset > 15; offset-=16) {
			byte[] nextSum = Arrays.copyOfRange(segment, (offset-16 < 0 ? 0 : offset-16), offset);
			checksumShort += ByteBuffer.wrap(nextSum).order(be).getShort();
		}
		byte[] checksum = ByteBuffer.allocate(2).order(be).putShort(checksumShort).array();
		return new byte[] {(byte) (~checksum[0] & 0xff), (byte) (~checksum[1] & 0xff)};
	}
	
	private static byte[] readBinary(String path) {
		try {
			Path p = Paths.get(path);
			return Files.readAllBytes(p);
		} catch (IOException e) {
			System.err.println("Caught " + e.getClass());
			e.printStackTrace();
			return null;
		} 
	}
	
	private static void printDigest(byte[] file) {
		MessageDigest md;
		byte[] digest = {(byte)-1};
		try {
			md = MessageDigest.getInstance("MD5");
			 digest = md.digest(file);
		} catch (NoSuchAlgorithmException e) {
			System.err.println("Caught " + e.getClass());
			e.printStackTrace();
		}
		System.out.println(javax.xml.bind.DatatypeConverter.printHexBinary(digest));

	}
	
	static class Header {
		
		private int seqNum, ackNum;
		private short rwnd, checksum;
		private boolean ack, syn, fin;
		
		public Header(int seqNum, int ackNum, short rwnd, short checksum, boolean ack, boolean syn, boolean fin) {
			this.seqNum = seqNum;
			this.ackNum = ackNum;
			this.rwnd = rwnd;
			this.checksum = checksum;
			this.ack = ack;
			this.syn = syn;
			this.fin = fin;
		}
		
		public Header(ByteBuffer header) {
			byte[] seqBuf = new byte[4];
			this.seqNum = header.getInt(4);
			
			byte[] ackBuf = new byte[4];
			this.ackNum = header.getInt(8);
			
			byte[] flagsBuf = new byte[] {header.get(13)};
			BitSet bs = BitSet.valueOf(flagsBuf);
			this.fin = bs.get(0);
			this.syn = bs.get(1);
			this.ack = bs.get(4);
			
			byte[] rwndBuf = new byte[2];
			this.rwnd = header.getShort(14);
			
			byte[] checksumBuf = new byte[2];
			this.checksum = header.getShort(16);
			
		
		}
		
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
	
	static class Window {
		
		//Dyn array of latest segments/acks recvd by recv thread, then pass control to sender
		
		private int seq, recvAck;
		private short rwnd, cwnd, ssthresh, lba, numUnacked;
		private boolean[] acked;		
		
		public Window(int seq, int recvAck) {
			this.seq = seq;
			this.recvAck = recvAck;
			this.ssthresh = 32736;
			this.cwnd = size;
			this.rwnd = 32736;
			this.lba = 0;
			this.acked = new boolean[32736];
		}
		
		public synchronized int getSeq() {
			return this.seq;
		}

		public synchronized void incSeq(int num) {
			this.seq += num;
		}
		
		public synchronized int getRecvAck() {
			return this.recvAck;
		}
		
		public synchronized void incRecvAck(int num) {
			this.recvAck += num;
		}
		
		public synchronized short getRwnd() {
			return rwnd;
		}

		public synchronized void setRwnd(short rwnd) {
			this.rwnd = rwnd;
		}

		public synchronized short getCwnd() {
			return cwnd;
		}

		public synchronized void setCwnd(short cwnd) {
			this.cwnd = cwnd;
		}

		public synchronized short getSsthresh() {
			return ssthresh;
		}

		public synchronized void setSsthresh(short ssthresh) {
			this.ssthresh = ssthresh;
		}
		
		public synchronized short getNumUnacked() {
			return this.numUnacked;
		}
		
		public synchronized void setNumUnacked(short numUnacked) {
			this.numUnacked = numUnacked;
		}
		
		public synchronized void incNumUnacked(short num) {
			setNumUnacked((short) (getNumUnacked() + num));
		}
		
		public synchronized short getLba() {
			return this.lba;
		}
		
		public synchronized void setLba(short lba) {
			this.lba = lba;
		}
	
		public synchronized void reportTimeout() {
			setSsthresh((short) (cwnd/2));
			setCwnd((short) size);
		}
		
		public synchronized void reportTripleAck() {
			setSsthresh((short) ((cwnd/2) + size));
			setCwnd((short) (cwnd/2));
		}
		
		public synchronized boolean inSlowStart() {
			return (cwnd < ssthresh ? true : false);
		}
		
		public synchronized boolean canSendMore() {
			return (numUnacked < Math.min(cwnd-(size+1), rwnd-(size+1)) ? true : false);
		}
		
	}
	
}

//System.out.println(segment.length);
//System.out.println(javax.xml.bind.DatatypeConverter.printHexBinary(checksum));
