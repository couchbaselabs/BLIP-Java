package com.couchbase.blip;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * A class representing a BLIP connection over a web socket, which can send and receive BLIP messages
 * over a network connection. Messages are sent as binary data by first converting them into their binary
 * representation, and then converting them back into Message objects on the other side. Messages are
 * multiplexed by splitting their binary data into blocks called frames and sending those frames one at a time.
 * Because of this, multiple messages can be sent and received at once.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Connection}, {@link WebSocketListener}, {@link Message}
 */
public final class WebSocketConnection extends Connection
{
	private WebSocket socket;
	private URI       uri;
	boolean           closed;
	
	final ArrayDeque<Message>       outMessages = new ArrayDeque<Message>();
	final HashMap<Integer, Message> inRequests  = new HashMap<Integer, Message>();
	final HashMap<Integer, Message> inReplies   = new HashMap<Integer, Message>();
	
	private Thread workerThread;
	final Object lock = new Object();
	
	
	// Internal use only
	WebSocketConnection(WebSocket socket)
	{
		this.socket = socket;
		this.startThread();
	}	
	
	/**
	 * Creates a new connection with the specified URI
	 * @param uri the URI
	 * @throws NullPointerException if the URI is null
	 * @throws URISyntaxException if the URI is invalid
	 */
	public WebSocketConnection(String uri) throws URISyntaxException
	{
		this(new URI(uri));
	}
	
	/**
	 * Creates a new connection with the specified URI
	 * @param uri the URI
	 * @throws NullPointerException if the URI is null
	 */
	public WebSocketConnection(URI uri)
	{
		this.uri = uri;
	}
	

	/**
	 * Opens this connection to the network
	 */
	@Override
	public void connect()
	{
		if (this.closed)         throw new IllegalStateException("Connection has already been closed");
		if (this.socket != null) throw new IllegalStateException("Connection is already open");
		
		WebSocketClient socket = new WebSocketClient(this.uri)
		{
			@Override
			public void onOpen(ServerHandshake handshake)
			{
			}
			
			@Override
			public void onClose(int code, String reason, boolean remote)
			{
				WebSocketConnection.this.closed = true;
			}

			@Override
			public void onMessage(String message) {}
			
			@Override
			public void onMessage(ByteBuffer message)
			{
				WebSocketConnection.this.onFrame(message);
			}
			
			@Override
			public void onError(Exception error)
			{
				if (error != null) error.printStackTrace();
			}
		};
		this.socket = socket;
		socket.connect();
	}
	
	/**
	 * Closes this connection
	 */
	@Override
	public void close()
	{
		this.socket.close();
		this.closed = true;
	}
	
	
	void startThread()
	{
		this.workerThread = new Thread()
		{
			@Override
			public void run()
			{
				ArrayDeque<Message> messages = WebSocketConnection.this.outMessages;
				Object              lock     = WebSocketConnection.this.lock;
				
				while (true)
				{
					try
					{
						Message msg = messages.peek();
						if (msg == null)
						{
							synchronized (lock)
							{
								lock.wait();
							}
						}
						else
						{
							ByteBuffer frame = msg.makeNextFrame(0x8000);
							
						}
					}
					catch (InterruptedException e) {}
				}
			}
		};
		this.workerThread.start();
	}
	
	void shutdown()
	{
		
	}
	
	
	void onFrame(ByteBuffer frame)
	{	
		// Frame sanity checks (magic number & frame size)
		if (frame.rewind().remaining() < 12
         || frame.getInt(0) != Message.FRAME_MAGIC
         || frame.getShort(10) < 12)
		{
			// All of these are fatal errors, so we have to close the connection:
			Logger.fatal("Bad frame");
			this.socket.close();
			this.closed = true;
			return;
		}
		
		int number = frame.getInt(4);
		int flags  = frame.getShort(8);
		int type   = flags & Message.TYPE_MASK;
		Message msg;
		
		// Message is a request:
		if      (type == Message.MSG)
		{
			msg = this.inRequests.get(number);
			if (msg == null)
			{
				
			}
		}
		
		// Message is a reply:
		else if (type == Message.RPY)
		{
			msg = this.inReplies.get(number);
			
		}
		
		// Message is an error:
		else if (type == Message.ERR)
		{
			
		}
		
		// Message is a request acknowledgement:
		else if (type == Message.ACKMSG)
		{
			
		}
		
		// Message is a reply acknowledgement:
		else if (type == Message.ACKRPY)
		{
			
		}
		
		// Unknown message type:
		else
		{
			
		}
		
		if ((flags & Message.MORECOMING) == 0)
		{
			
		}
	}
	
	
	/**
	 * Sends a request message through this connection
	 * @param request the request message to send
	 * @return the response message
	 */
	@Override
	public Message sendRequest(Message request)
	{
		// This will be the completed message when all frames are received:
		Message msg = new Message();

		return msg;
	}
	
	
	/**
	 * Returns the underlying URI of this connection
	 * @return the underlying URI of this connection
	 */
	public URI getURI()
	{
		return this.uri;
	}
	
	
	@Override
	public boolean equals(Object obj)
	{
		return (obj instanceof WebSocketConnection) && this.equals((WebSocketConnection)obj);
	}
	
	public boolean equals(WebSocketConnection connection)
	{
		return this.socket.getRemoteSocketAddress().equals(connection.socket.getRemoteSocketAddress());
	}
	
	@Override
	public int hashCode()
	{
		return this.socket.getRemoteSocketAddress().hashCode();
	}
	
	@Override
	public String toString()
	{
		return "BLIP connection (socket " + this.socket.getLocalSocketAddress() + ')';
	}
}
