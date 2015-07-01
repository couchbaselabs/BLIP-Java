package com.couchbase.blip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.nio.ByteBuffer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A listener which manages a group of BLIP connections.
 * 
 * @author Jed Foss-Alfke
 * @see {@link WebSocketConnection}, {@link Message}
 */
public final class WebSocketListener
{
	private WebSocketServer   socket;
	private InetSocketAddress address;
	private boolean           stopped;
	
	final HashMap<InetSocketAddress, WebSocketConnection> connections = new HashMap<InetSocketAddress, WebSocketConnection>();
	
	
	/**
	 * Creates a new WebSocketListener that listens on the specified port
	 * @param port
	 * @throws UnknownHostException
	 */
	public WebSocketListener(int port) throws UnknownHostException
	{
		this(new InetSocketAddress(port));
	}
	
	/**
	 * Creates a new WebSocketListener that listens on the specified address
	 * @param address
	 */
	public WebSocketListener(InetSocketAddress address)
	{
		this.address = address;
	}
	
	
	/**
	 * Starts the listener
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
				WebSocketListener.this.connections.put(socket.getRemoteSocketAddress(), connection);
				
			}
			
			@Override
			public void onClose(WebSocket socket, int code, String reason, boolean remote)
			{
				WebSocketConnection connection = WebSocketListener.this.connections.remove(socket.getRemoteSocketAddress());
				ConnectionDelegate del = connection.delegate;
				if (del != null)
				{
					del.onClose(connection);
				}
			}

			@Override
			public void onMessage(WebSocket socket, String message) {}
			
			@Override
			public void onMessage(WebSocket socket, ByteBuffer message)
			{
				WebSocketListener.this.connections.get(socket.getRemoteSocketAddress()).onFrame(message);
			}
			
			@Override
			public void onError(WebSocket socket, Exception error)
			{
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
	
	public InetSocketAddress getAddress()
	{
		return this.address;
	}
	
	
	@Override
	public boolean equals(Object obj)
	{
		return super.equals(obj);
	}
	
	@Override
	public int hashCode()
	{
		return super.hashCode();
	}
	
	@Override
	public String toString()
	{
		return super.toString();
	}
}
