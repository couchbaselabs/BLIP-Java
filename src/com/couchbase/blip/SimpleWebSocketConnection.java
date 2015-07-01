package com.couchbase.blip;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 * A simple class representing a connection over a web socket.
 * Sends messages one at a time, without doing any multiplexing.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Connection}, {@link SimpleWebSocketListener}
 * @deprecated
 */
@Deprecated
public final class SimpleWebSocketConnection extends Connection
{
	private URI               uri;	
	private WebSocket         socket;
	private boolean           closed;
	private transient Message pendingResponse = null;
	private transient int     currentNumber   = 0;
	
	
	/**
	 * Creates a new SimpleWebSocketConnection with the specified URI
	 * @param uri
	 * @throws URISyntaxException
	 */
	public SimpleWebSocketConnection(String uri) throws URISyntaxException
	{
		this(new URI(uri));
	}
	
	/**
	 * Creates a new SimpleWebSocketConnection with the specified URI
	 * @param uri
	 */
	public SimpleWebSocketConnection(URI uri)
	{
		this.uri = uri;
	}
	
	
	protected SimpleWebSocketConnection(WebSocket socket)
	{
		this.socket = socket;
	}
	
	
	// Message handler:
	final void onMessage(ByteBuffer message)
	{
		Message msgObj = new Message();
		msgObj.decodeFromSingleFrame(message);
		ConnectionDelegate delegate = this.delegate;	
		if (delegate != null)
		{
			int msgFlags = message.getShort(8);
			int msgType  = msgFlags & Message.TYPE_MASK;
			
			// We can't handle incoming messages that are split into multiple frames:
			if ((msgFlags & Message.MORECOMING) != 0)
			{
				
				return;
			}
			
			if (msgType == Message.MSG)
				delegate.onRequest(SimpleWebSocketConnection.this, msgObj);
			else if (msgType == Message.RPY)
				delegate.onResponse(SimpleWebSocketConnection.this, msgObj);
			else if (msgType == Message.ERR)
				;
			else if (msgType == Message.ACKMSG)
				;
			else if (msgType == Message.ACKRPY)
				;
			else
				;
		}
	}
	
	
	/**
	 * Opens the connection
	 */
	public final void connect()
	{
		if (this.closed)         throw new IllegalStateException("Connection has been closed");
		if (this.socket != null) throw new IllegalStateException("Connection is already connected");
		 
		WebSocketClient socket = new WebSocketClient(this.uri)
		{		
			@Override
			public void onOpen(ServerHandshake handshake)
			{
				//SimpleWebSocketConnection.this.delegate.onOpen(SimpleWebSocketConnection.this);
			}
			
			@Override
			public void onClose(int code, String reason, boolean remote)
			{
				SimpleWebSocketConnection.this.closed = true;
				ConnectionDelegate delegate = SimpleWebSocketConnection.this.delegate;
				if (delegate != null)
				{
					delegate.onClose(SimpleWebSocketConnection.this);
				}
			}

			@Override
			public void onError(Exception err)
			{
				//SimpleWebSocketConnection.this.delegate.onError(SimpleWebSocketConnection.this);
			}

			@Override
			public void onMessage(String message) {}
			
			@Override
			public void onMessage(ByteBuffer message)
			{
				SimpleWebSocketConnection.this.onMessage(message);
			}
		};
		this.socket = socket;
		socket.connect();
	}

	/**
	 * Closes the connection
	 */
	public final void close()
	{
		this.socket.close();
		this.closed = true;
	}
	
	@Override
	public Message sendRequest(Message request)
	{
		ByteBuffer frame = request.encodeAsSingleFrame();
		this.socket.send(frame);
		Message pendingResponse = new Message();
		pendingResponse.number = request.number;
		return (this.pendingResponse = pendingResponse);
	}
}
