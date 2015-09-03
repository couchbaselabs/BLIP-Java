package com.couchbase.blip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.nio.ByteBuffer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A server which manages a group of BLIP web socket connections. A server listens on a specific port for incoming BLIP connections,
 * and manages an internal collection of BLIP connections which are connected to it.
 * 
 * @author Jed Foss-Alfke
 * @see {@link WebSocketConnection}, {@link Message}
 */
public final class Server
{	
	private WebSocketServer   socket;
	private InetSocketAddress address;
	private boolean           stopped;
	
	final IdentityHashMap<WebSocket, WebSocketConnection> connections = new IdentityHashMap<WebSocket, WebSocketConnection>();
	
	ServerListener listener;
	
	
	/**
	 * Creates a new WebSocketListener that listens on the specified port
	 * @param port
	 * @throws UnknownHostException if the port is invalid
	 */
	public Server(int port) throws UnknownHostException
	{
		this(new InetSocketAddress(port));
	}
	
	/**
	 * Creates a new WebSocketListener that listens on the specified address
	 * @param address
	 * @throws NullPointerException if the address is null
	 */
	public Server(InetSocketAddress address)
	{
		if (address == null) throw new NullPointerException("Address is null");
		this.address = address;
	}
	
	
	/**
	 * Starts the listener
	 * @throws IllegalStateException if the listener is currently running or has been run before
	 */
	public void start()
	{
		if (this.stopped)        throw new IllegalStateException("Listener has been stopped");
		if (this.socket != null) throw new IllegalStateException("Listener is already running");
		
		WebSocketServer socket = new WebSocketServer(this.address)
		{
			@Override
			public void onOpen(WebSocket socket, ClientHandshake handshake)
			{				
				WebSocketConnection connection = new WebSocketConnection(socket);
				Server.this.connections.put(socket, connection);
				
				ServerListener del = Server.this.listener;
				if (del != null)
					del.connectionOpened(Server.this, connection);
			}
			
			@Override
			public void onClose(WebSocket socket, int code, String reason, boolean remote)
			{
				WebSocketConnection connection = Server.this.connections.remove(socket);				
				
				ServerListener del = Server.this.listener;
				if (del != null)
					del.connectionClosed(Server.this, connection);
				
				connection.shutdown();
			}

			@Override
			public void onMessage(WebSocket socket, String message)
			{
				// TODO This should be a fatal error
			}
			
			@Override
			public void onMessage(WebSocket socket, ByteBuffer message)
			{
				WebSocketConnection c = Server.this.connections.get(socket);
				if (c != null)
				{
					c.onFrame(message);
				}
			}
			
			@Override
			public void onError(WebSocket socket, Exception error)
			{
				// TODO handle errors better than this
				if (error != null) error.printStackTrace();
			}
		};
		this.socket = socket;
		socket.start();
	}
	
	/**
	 * Stops the listener
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public void stop() throws IOException, InterruptedException
	{
		this.socket.stop();
		this.stopped = true;
	}
	
	/**
	 * Returns the IP Socket Address this listener is listening on
	 * @return the listener's IP Socket Address
	 */
	public InetSocketAddress getAddress()
	{
		return this.address;
	}
	
	/**
	 * Returns the delegate for this listener
	 * @return the delegate
	 */
	public ServerListener getListener()
	{
		return this.listener;
	}
	
	/**
	 * Sets the delegate for this listener
	 * @param delegate the delegate
	 */
	public void setListener(ServerListener listener)
	{
		this.listener = listener;
	}
	
	
	@Override
	public String toString()
	{
		return "BLIP server (" + this.address + ")";
	}
}
