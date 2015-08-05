package com.couchbase.blip;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * A class representing a BLIP connection over a web socket, which can send and receive BLIP messages
 * over a network connection. Messages are sent as binary data by first converting them into their binary
 * representation, and then converting them back into Message objects on the other side. Messages are
 * multiplexed by splitting their binary data into blocks called frames and sending those frames one at a time.
 * Because of this, multiple messages can be sent and received at once.
 * <br><br>
 * Once a connection is opened using its {@code connect()} method, it cannot be garbage collected until it is manually
 * shut down by calling its {@code close()} method. Therefore, to avoid thread and memory leaks, all connections must be
 * shut down manually once the program is finished using them.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Connection}, {@link WebSocketListener}, {@link Message}
 */
public final class WebSocketConnection extends Connection
{
	WebSocket              socket;
	URI                    uri;
	volatile AtomicBoolean isClosed = new AtomicBoolean(false);
	
	final ConcurrentLinkedQueue<Message> outMessages = new ConcurrentLinkedQueue<Message>();
	final HashMap<Integer, Message>      inRequests  = new HashMap<Integer, Message>();
	final HashMap<Integer, Message>      inReplies   = new HashMap<Integer, Message>();
	
	private Thread workerThread;
	
	
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
		if (uri == null) throw new NullPointerException("URI is null");
		this.uri = uri;
	}
	

	/**
	 * Opens this connection to the network
	 * @throws IllegalStateException if this connection has already been opened
	 */
	@Override
	public void connect()
	{
		if (this.socket != null) throw new IllegalStateException("Connection has already been opened");
		
		WebSocketClient socket = new WebSocketClient(this.uri)
		{
			@Override
			public void onOpen(ServerHandshake handshake) {}
			
			@Override
			public void onClose(int code, String reason, boolean remote)
			{
				WebSocketConnection.this.shutdown();
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
		this.startThread();
	}
	
	/**
	 * Closes this connection
	 */
	@Override
	public void close()
	{
		this.socket.close();
	}
	
	
	// Starts the worker thread
	void startThread()
	{
		// Create the thread:
		Thread workerThread = new Thread()
		{
			@Override
			public void run()
			{
				final ConcurrentLinkedQueue<Message> messages = WebSocketConnection.this.outMessages;
				final WebSocket                      socket   = WebSocketConnection.this.socket;
				
				while (true)
				{
					// Check if there are any outgoing messages in the queue:
					if (!messages.isEmpty())
					{	
						// If there are, iterate over all of them and send out one frame each:
						for (Iterator<Message> iter = messages.iterator(); iter.hasNext();)
						{
							Message      msg = iter.next();
							ByteBuffer frame = msg.makeNextFrame(0x800);
							
							if (frame != null)
							{
								System.out.println("sent frame: " + Message.debugPrintFrame(frame));
								socket.send(frame);
							}
							else
							{
								iter.remove();
							}
						}
					}
					// Otherwise, wait until there are:
					else
					{					
						try
						{
							synchronized (this)
							{
								this.wait();
							}
						}
						// If the thread is interrupted, check to see if we should shut down:
						catch (InterruptedException e)
						{
							if (WebSocketConnection.this.isClosed.get())
							{
								// debug output
								System.out.println("Worker thread shutting down");
								return;
							}
						}
					}
					
					// Check for interrupts here too, since InterruptedException clears the interrupted flag:
					if (Thread.interrupted())
					{
						if (WebSocketConnection.this.isClosed.get())
						{
							// debug output
							System.out.println("Worker thread shutting down");
							return;
						}
					}
				}
			}
		};
		this.workerThread = workerThread;
		workerThread.setName("BLIP Worker Thread");
		workerThread.start();
	}
	
	void shutdown()
	{
		this.isClosed.set(true);
		this.workerThread.interrupt();
		this.workerThread = null;	// dispose of the thread
	}
	
	
	void onFrame(ByteBuffer frame)
	{
		// debug output
		System.out.println("got frame:  " + Message.debugPrintFrame(frame));
		
		int number = Message.readVarint(frame);
		int flags  = Message.readVarint(frame);
		int type   = flags & Message.TYPE_MASK;
		
		Message msg = null;
		
		if      (type == Message.MSG)
		{
			msg = this.inRequests.get(number);
			if (msg == null)
			{
				msg = new Message();
				msg.readFirstFrame(frame, number, flags);
				this.inRequests.put(number, msg);
			}
			else
			{
				msg.readNextFrame(frame, flags);				
				if ((flags & Message.MORECOMING) == 0)
				{
					this.inRequests.remove(number);
					ConnectionDelegate del = this.delegate;
					if (del != null)
						del.onRequest(this, msg);
					
					// debug output
					System.out.println("Message obj successfully processed (connection obj #" + System.identityHashCode(this) + ") : " + msg.toStringWithProperties());
				}
			}
		}
		else if (type == Message.RPY || type == Message.ERR)
		{
			msg = this.inReplies.get(number);
			if (msg != null)
			{
				if (!msg.stateFlag)
				{
					msg.stateFlag = true;
					msg.readFirstFrame(frame, number, flags);
				}
				else
				{
					msg.readNextFrame(frame, flags);
					if ((flags & Message.MORECOMING) == 0)
					{						
						this.inReplies.remove(number);
						
						{
							MessageDelegate del = msg.delegate;
							if (del != null) del.onCompleted(msg);
						}
						
						{
							ConnectionDelegate del = this.delegate;
							if (del != null) del.onResponse(this, msg);
						}
					}
				}
			}
		}
		else if (type == Message.ACKMSG)
		{
			
		}
		else if (type == Message.ACKRPY)
		{
			
		}
		else
		{
			
		}
	}
	
	
	// Adds a message to the outgoing queue and wakes up the worker thread if necessary
	private void enqueueMessage(Message msg)
	{
		// debug output
		System.out.println("Sending message obj: " + msg.toStringWithProperties());
		
		msg.isMutable = false;
		boolean flag = this.outMessages.isEmpty();
		this.outMessages.add(msg);
		if (flag)
		{
			synchronized (this.workerThread)
			{
				this.workerThread.notify();
			}
		}
	}
	
	/**
	 * Sends a request message through this connection
	 * @param request the request message to send
	 * @return the reply message, or null if the request's noreply flag is set
	 */
	@Override
	public Message sendRequest(Message request)
	{
		if (!request.isMine) throw new UnsupportedOperationException("Cannot send a message that is not mine");
		
		// This will be the reply to this message when all frames are received:
		Message msg = null;		
		if (!request.isNoReply())
		{
			msg = new Message();
			int number = request.number;
			msg.number = number; 
			this.inReplies.put(number, msg);
		}
		this.enqueueMessage(request);
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
	
	
	// FIXME fix NullPointerExceptions:
	
	@Override
	public boolean equals(Object that)
	{
		return (that instanceof WebSocketConnection) && this.equals((WebSocketConnection)that);
	}
	
	public boolean equals(WebSocketConnection that)
	{
		return this.socket.getRemoteSocketAddress().equals(that.socket.getRemoteSocketAddress());
	}
	
	@Override
	public int hashCode()
	{
		return this.socket.getRemoteSocketAddress().hashCode();
	}
	
	/**
	 * Returns a textual representation of this connection
	 * @return a string describing this connection
	 */
	@Override
	public String toString()
	{
		return "BLIP connection (socket " + this.socket.getRemoteSocketAddress() + ')';
	}
}
