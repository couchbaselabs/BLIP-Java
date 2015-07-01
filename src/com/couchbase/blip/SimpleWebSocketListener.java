package com.couchbase.blip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A simple class which handles incoming web socket connections.
 * Processes messages one at a time without any multiplexing.
 * 
 * @author Jed Foss-Alfke
 * @see {@link SimpleWebSocketConnection}
 * @deprecated
 */
@Deprecated
public final class SimpleWebSocketListener
{	
	private WebSocketServer   socket;
	private InetSocketAddress address;
	private final Map<WebSocket, SimpleWebSocketConnection> connections = new HashMap<WebSocket, SimpleWebSocketConnection>();
	
	public SimpleWebSocketListener(int port) throws UnknownHostException
	{
		this(new InetSocketAddress(port));
	}
	
	public SimpleWebSocketListener(InetSocketAddress address)
	{
		this.address = address;
	}
	
	
	final void onOpen(WebSocket socket, ClientHandshake handshake)
	{
		Logger.log("Connection opened");
		
		SimpleWebSocketConnection connection = new SimpleWebSocketConnection(socket);
		this.connections.put(socket, connection);
	}
	
	final void onClose(WebSocket socket, int code, String reason, boolean remote)
	{
		Logger.log("Connection closed");
		
		SimpleWebSocketConnection connection = this.connections.get(socket);
		if (connection != null)
		{
			if (connection.delegate != null) connection.delegate.onClose(connection);
			this.connections.remove(socket);
		}
	}
	
	final void onError(WebSocket socket, Exception err)
	{
		String msg;
		if (socket == null) msg = "Error: " + err;
		else                msg = "Error in socket " + socket.getLocalSocketAddress() + ": " ;
		Logger.warn(msg, err);
	}
	
	final void onMessage(WebSocket socket, ByteBuffer message)
	{
		Logger.log("Message (" + message.capacity() + " bytes)");
		
		Message msgObj = new Message();
		msgObj.decodeFromSingleFrame(message);
		
		SimpleWebSocketConnection connection = this.connections.get(socket);
		if (connection != null)
		{
			ConnectionDelegate delegate = connection.delegate;
			if (delegate != null)
			{
				int msgType = msgObj.flags & Message.TYPE_MASK;

				if      (msgType == Message.MSG)
					connection.delegate.onRequest(connection, msgObj);
				else if (msgType == Message.RPY)
					connection.delegate.onResponse(connection, msgObj);
				else
					;
			}
		}
	}
	
	
	public void start()
	{
		if (this.socket != null) throw new IllegalStateException("Listener is already running");
		
		this.socket = new WebSocketServer(this.address)
		{
			@Override
			public void onOpen(WebSocket socket, ClientHandshake handshake)
			{
				SimpleWebSocketListener.this.onOpen(socket, handshake);
			}
			
			@Override
			public void onClose(WebSocket socket, int code, String reason, boolean remote)
			{
				SimpleWebSocketListener.this.onClose(socket, code, reason, remote);
			}

			@Override
			public void onError(WebSocket socket, Exception err)
			{
				SimpleWebSocketListener.this.onError(socket, err);
			}

			@Override
			public void onMessage(WebSocket socket, String message) {}

			@Override
			public void onMessage(WebSocket socket, ByteBuffer message)
			{
				SimpleWebSocketListener.this.onMessage(socket, message);
			}
		};	
		this.socket.start();
	}
	
	public void stop() throws InterruptedException, IOException
	{
		this.socket.stop();
	}
	
	public int getPort()
	{
		return this.socket.getPort();
	}
}
