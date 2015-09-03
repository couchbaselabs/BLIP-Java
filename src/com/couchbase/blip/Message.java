package com.couchbase.blip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// TODO Masterlist:
//
//      - Error handling
//		  - Fatal errors:
// 		    - Bad varint encoding
// 		    - Missing header value (either no flags, or just an empty frame)
//		    - Receiving a non-binary WebSocket message
//		  - Frame errors:
//		    - Unknown message type
//		    - Message number refers to already-completed message
//		    - A property string contains invalid UTF-8
//		    - Property data's length field exceeds remaining frame data
//		    - Property data, if non-empty, does not end with a NUL byte
//          - Body of a compressed frame fails to decompress
//      - Message compression

	
/** 
 * The Message class represents messages sent and received by applications running the BLIP protocol.
 * <br><br>
 * A message is comprised of two parts: the properties, which are a set of string key-value pairs,
 * and the body, which is a block of binary data.
 * <br><br>
 * There are two types of messages: requests and responses. Any request message sent to an application
 * must always be responsed to with a response message, though the response message does not necessarily
 * have to contain any data.
 * 
 * @author Jed Foss-Alfke
 * @see <a href="https://github.com/couchbaselabs/BLIP-Cocoa/blob/master/Docs/BLIP%20Protocol%20v1.md">The BLIP Protocol</a>,
 *      {@link Connection}
 */
public final class Message implements Comparable<Message>
{	
	// Constant values used in message encoding/decoding and transport:
	
	// Message types
	static final int MSG                  = 0x00;
	static final int RPY                  = 0x01;
	static final int ERR                  = 0x02;
	static final int ACKMSG               = 0x04;
	static final int ACKRPY               = 0x05;
	
	static final int TYPE_MASK            = 0x07;
	
	// Frame flags
	static final int COMPRESSED           = 0x08;
	static final int URGENT               = 0x10;
	static final int NOREPLY              = 0x20;
	static final int MORECOMING           = 0x40;
	static final int META                 = 0x80;
	
	static final int MAX_FLAG             = 0xFF;
	
	// Error message codes ("Error-Code" property)
	public static final int ERROR_BAD_REQUEST    = 400;
	public static final int ERROR_FORBIDDEN      = 403;
	public static final int ERROR_NOT_FOUND      = 404;
	public static final int ERROR_BAD_RANGE      = 416;
	public static final int ERROR_HANDLER_FAILED = 501;
	public static final int ERROR_UNSPECIFIED    = 599;
	
	
	// Array of common property names and values that can be abbreviated as single-byte strings.
	private static final String[] commonProperties =
	{ 
		"Profile",
		"Error-Code",
		"Error-Domain",
		
		"Content-Type",
		"application/json",
		"application/octet-stream",
		"text/plain; charset=UTF-8",
		"text/xml",
		
		"Accept",
		"Cache-Control",
		"must-revalidate",
		"If-Match",
		"If-None-Match",
		"Location"
	};
	
	// Map that contains the common property names mapped to their respective byte representations
	private static final HashMap<String, Byte> propertyAbbreviations = new HashMap<String, Byte>();
	static
	{
		for (int i = commonProperties.length; i != 0;)
		{
			propertyAbbreviations.put(commonProperties[--i], (byte)(i + 1));
		}
	}
	
	
	// For converting Java strings to and from C strings
	private static final Charset cStringCharset = Charset.forName("ISO-8859-1");
	
	// Default body so body is not null
	private static final ByteBuffer emptyBody = ByteBuffer.allocate(0);
	
	
	protected static String debugPrintFrame(ByteBuffer frame)
	{
		int pos = frame.position();
		int lim = frame.limit();
		StringBuilder sb1 = new StringBuilder("{");
		StringBuilder sb2 = new StringBuilder();
		for (int i = pos;;)
		{
			byte b = frame.get(i);
			sb1.append(Integer.toHexString(b & 0xFF));
			sb2.append((b > 0x1F) ? (char)b : 'á');
			if (++i < lim) sb1.append(", ");
			else break;
		}
		sb1.append("} : \"");
		sb1.append(sb2);
		sb1.append('"');
		return sb1.toString();
	}
	
	
	transient Connection      connection;
	transient Message         linkedMsg;
	
	int                       number;
	int                       flags;

	final Map<String, String> properties = new HashMap<String, String>();
	ByteBuffer                body       = emptyBody;
	
	transient ReplyListener   listener;
	
	boolean                   isMine;
	boolean	                  isMutable;
	boolean                   isCompressed;
	boolean                   isUrgent;
	
	
	// Internal use only
	Message(Connection connection, int number, int flags, boolean outgoing)
	{
		this.connection = connection;
		this.number     = number;
		this.flags      = flags;
		this.isMine     = outgoing;
		this.isMutable  = outgoing;
	}
	
	
	// Internal fields for encoding/decoding:
	transient boolean                  stateFlag;
	private transient ByteBuffer       codingBuffer;
	private transient Object           gzipIn, gzipOut;
	
	
	
	private String getTypeName()
	{
		switch (this.flags & TYPE_MASK)
		{
		case MSG: 	 return "Request";
		case RPY: 	 return "Response";
		case ERR: 	 return "Error";
		case ACKMSG: return "Request-Ack";
		case ACKRPY: return "Response-Ack";
		default: 	 return "Message";
		}
	}
	
	
	// Reads a varint out of a bytebuffer, starting at its current offset
	static int readVarint(ByteBuffer frame)
	{
		int result = 0;
		int i; 
		result  =  (i = frame.get()) & 127;
		if ((i & 127) != i) result |= ((i = frame.get()) & 127) << 7;
		if ((i & 127) != i) result |= ((i = frame.get()) & 127) << 14;
		if ((i & 127) != i) result |= ((i = frame.get()) & 127) << 21;
		if ((i & 127) != i) result |= ((i = frame.get()) & 127) << 28;
		if ((i & 127) != i) throw new NumberFormatException("Invalid varint");
		return result;
	}
	
	// Writes a varint into a bytebuffer, starting at its current offset
	// We don't have to do range checks or reallocation here like we do with C string writing since varints are only in the first 12 bytes of the frame at most
	static void writeVarint(ByteBuffer frame, int varint)
	{
		do {
			if  ((varint         & 127) == varint) break; frame.put((byte)(varint | 128));
			if (((varint >>>= 7) & 127) == varint) break; frame.put((byte)(varint | 128));
			if (((varint >>>= 7) & 127) == varint) break; frame.put((byte)(varint | 128));
			if (((varint >>>= 7) & 127) == varint) break; frame.put((byte)(varint | 128));
			if (((varint >>>= 7) & 127) == varint) break; frame.put((byte)(varint | 128));
		} while (false);
		frame.put((byte)varint);
	}
	
	// Reads a C string
	private static String readCString(ByteBuffer frame) throws CharacterCodingException
	{
		ByteBuffer start = frame.duplicate();
		
		byte b = frame.get();
		if ((b & 0x1f) == b)
		{
			String s = null;
			if (b < commonProperties.length && frame.get() == 0) s = commonProperties[b - 1];
			return s;
		}
		
		while (frame.get() != 0);
		return cStringCharset.newDecoder().decode((ByteBuffer)start.limit(frame.position()-1)).toString();
	}
	
	// Writes a C string and expands the frame if necessary
	// FIXME: Property strings containing nul ('\0') characters will corrupt the frame when they are written.
	//        Figure out how to throw an exception here if any nul chars are encountered
	private static ByteBuffer writeCString(ByteBuffer frame, String string)
	{
		Byte b = propertyAbbreviations.get(string);
		if (b != null)
		{
			if (frame.remaining() < 2) frame = ByteBuffer.allocate(frame.capacity() << 1).put((ByteBuffer)frame.rewind());
			frame.put(b);
			frame.put((byte)0);
		}
		else
		{
			CharsetEncoder cStringEncoder = cStringCharset.newEncoder();
			
			CharBuffer chars = CharBuffer.wrap(string);
			for (int size = frame.capacity();; cStringEncoder.flush(frame))
			{
				cStringEncoder.reset();
				if (cStringEncoder.encode(chars, frame, true) == CoderResult.OVERFLOW)
				{
					// debug output
					// System.out.println("overflow, reallocating to size " + (size << 1) + " (printing \"" + string + "\")");
					frame = ByteBuffer.allocate(size = (size << 1)).put((ByteBuffer)frame.rewind());
				}
				else break;
			}
			cStringEncoder.flush(frame);
			frame.put((byte)0);
		}
		return frame;
	}
	
	
	// Creates the first frame, which contains the message properties
	final ByteBuffer makeFirstFrame()
	{
		// Write the properties into a bytebuffer first:
		ByteBuffer frame;
		frame = ByteBuffer.allocate(0x20);
		for (Map.Entry<String, String> entry : this.properties.entrySet())
		{
			frame = writeCString(frame, entry.getKey());
			frame = writeCString(frame, entry.getValue());
		}
		int len = frame.position();
		frame.limit(len);
		frame.rewind();
		
		// Setup compression objects if this message should be compressed
		if (this.isCompressed)
		{
			try
			{
				this.gzipIn  = new ByteBufferInputStream(this.body);
				this.gzipOut = new GZIPOutputStream(new ByteArrayOutputStream());
			}
			catch (IOException e) {}
		}
		
		// Then make another bytebuffer and copy them into it after writing the header
		// This is because the header is written as varints and the space it will occupy
		// cannot be determined before writing the properties
		// Since the properties are almost always small, this has little cost
		ByteBuffer outFrame = ByteBuffer.allocate(len + 12);
		writeVarint(outFrame, this.number);
		writeVarint(outFrame, this.flags | MORECOMING);
		writeVarint(outFrame, len);
		outFrame.put(frame);
		outFrame.limit(outFrame.position());
		outFrame.rewind();
		return outFrame;
	}
	
	// Creates the next frame in line, starting with the first frame containing message properties
	// and then the remaining frames containing the message body split into pieces.
	// This method is called repeatedly until the entire message has been encoded.
	final ByteBuffer makeNextFrame(int maxLength)
	{
		if (this.stateFlag) return null;
		
		ByteBuffer frame = null;
		ByteBuffer buf   = this.codingBuffer;	
		if (buf == null)
		{
			this.codingBuffer = this.body.duplicate();
			frame = this.makeFirstFrame();
		}
		else if (this.isCompressed)
		{
			GZIPOutputStream gzipOut = (GZIPOutputStream)this.gzipOut;
			
		}
		else
		{
			int len   = buf.remaining();
			int flags = this.flags;
			if (len > maxLength)
			{
				len = maxLength;
				flags |= MORECOMING;
			}
			else
			{
				this.stateFlag = true;
			}
			frame = ByteBuffer.allocate(len + 12);
			writeVarint(frame, this.number);
			writeVarint(frame, flags);
			
			ByteBuffer slice = buf.slice();
			slice.limit(len);
			frame.put(slice);
			frame.limit(frame.position());
			buf.position(buf.position() + len);
			this.body.position(this.body.position() + len);
		}
		frame.rewind();
		return frame;
	}
	
	// Read the first frame, which in this implementation currently contains the properties block in its entirety and nothing afterward
	// TODO support implementations which don't require this
	final void readFirstFrame(ByteBuffer frame, int flags)
	{
		this.flags         = flags;
		this.isCompressed  = (flags & COMPRESSED) != 0;
		this.isUrgent      = (flags & URGENT)     != 0;
		int propertiesSize = frame.position() + readVarint(frame);
		if (propertiesSize >= frame.limit() || frame.get(propertiesSize) != 0)
		{
			throw new RuntimeException("Malformed properties block");
		}
		try
		{
			while (true)
			{
				String key = readCString(frame);
				if (frame.position() >= propertiesSize) throw new RuntimeException("Malformed properties block");
				String value = readCString(frame);
				this.properties.put(key, value);
				if (frame.position() >= propertiesSize) break;
			}
		}
		catch (CharacterCodingException e)
		{
			
		}
		if (this.isCompressed)
		{
			//this.gzip = new GZIPInputStream( );
			
		}
		this.body = ByteBuffer.allocate(0x80);
	}
	
	// Read a frame containing a piece of the message body
	final void readNextFrame(ByteBuffer frame, int flags)
	{
		this.flags = flags;
		ByteBuffer body = this.body;
		if (this.isCompressed)
		{
			GZIPInputStream gzip = (GZIPInputStream)this.gzipIn;
			//gzip.
		}
		else
		{
			if (frame.remaining() > body.remaining())
			{
				ByteBuffer newBuffer;
				int neededSize = body.position() + frame.remaining();
				if ((flags & MORECOMING) == 0)
				{
					newBuffer = ByteBuffer.allocate(neededSize);
				}
				else
				{
					int capacity = body.capacity();
					while (capacity < neededSize) capacity <<= 1;
					newBuffer = ByteBuffer.allocate(capacity);
				}
				newBuffer.put((ByteBuffer)body.limit(body.position()).rewind()).put(frame);
				this.body = newBuffer;
			}
			else
			{
				body.put(frame);
				if ((flags & MORECOMING) == 0) body.limit(body.position()).rewind();
			}
		}
	}
	
	
	/**
	 * Sends the message over its associated connection.
	 * @return the response to this message, if this message is a request
	 */
	public final Message send()
	{		
		return this.connection.sendMessage(this);
	}
	
	
	/**
	 * Creates a new reply to this message
	 * @return the reply
	 */
	public final Message newResponse()
	{
		if (this.linkedMsg == null)
		{
			if (!this.isRequest() || this.isNoReply()) throw new UnsupportedOperationException("Message cannot be replied to");
			this.linkedMsg = new Message(this.connection, this.number, RPY, true);
		}
		return this.linkedMsg;
	}
	
	
	/**
	 * Returns the type of this message
	 * @return the type of this message
	 */
	public final int getMessageType()
	{
		return this.flags & TYPE_MASK;
	}
	
	/**
	 * Returns true if this message is a request
	 * @return true if this message is a request
	 */
	public final boolean isRequest()
	{
		return (this.flags & TYPE_MASK) == MSG;
	}
	
	/**
	 * Returns true if this message is a reply
	 * @return true if this message is a reply
	 */
	public final boolean isReply()
	{
		return (this.flags & TYPE_MASK) == RPY;
	}
	
	public final boolean isError()
	{
		return (this.flags & TYPE_MASK) == ERR;
	}
	
	/**
	 * Returns true if this message is owned locally
	 * @return true if this message is owned locally
	 */
	public final boolean isMine()
	{
		return this.isMine;
	}
	
	/**
	 * Returns true if this message can be modified
	 * @return true if this message can be modified
	 */
	public final boolean isMutable()
	{
		return this.isMutable;
	}
	
	/**
	 * Returns true if this message is complete
	 * @return true if this message is complete
	 */
	public final boolean isComplete()
	{
		return (this.flags & MORECOMING) == 0;
	}
	
	/**
	 * Returns true if this message is urgent
	 * @return true if this message is urgent
	 */
	public final boolean isUrgent()
	{
		return this.isUrgent;
	}
	
	/**
	 * Sets the urgent status of this message.
	 * Urgent messages are given higher priority during sending and receiving
	 * @param urgent the urgent status
	 */
	public final void setUrgent(boolean isUrgent)
	{
		if (!this.isMutable) throw new IllegalStateException("Message is not mutable");
		this.isUrgent = isUrgent;
	}
	
	/**
	 * Returns true if this request should not be replied to
	 * @return true if this request should not be replied to
	 */
	public final boolean isNoReply()
	{
		return (this.flags & NOREPLY) != 0;
	}
	
	/**
	 * Sets the noreply status of this message
	 * @param noreply the noreply status
	 */
	public final void setNoReply(boolean noreply)
	{
		if (!this.isMutable) throw new IllegalStateException("Message is not mutable");
		if (noreply) this.flags |=  NOREPLY;
		else         this.flags &= ~NOREPLY;
	}
	
	/**
	 * Returns true if this message's body is compressed during transit
	 * @return true if this message's body is compressed
	 */
	public final boolean isCompressed()
	{
		return this.isCompressed;
	}
	
	/**
	 * Sets whether this message's body should be compressed during transit
	 * @param compressed whether this message's body should be compressed
	 */
	public final void setCompressed(boolean compressed)
	{
		if (!this.isMutable) throw new IllegalStateException("Message is not mutable");
		if (compressed) throw new UnsupportedOperationException("Compression not supported yet");
		this.isCompressed = compressed;
	}
	
	/**
	 * Returns the BLIP connection that created or received this message
	 * @return the message's BLIP connection
	 */
	public final Connection getConnection()
	{
		return this.connection;
	}
	
	/**
	 * Returns the listener for this message
	 * @return the listener for this message
	 */
	public final ReplyListener getListener()
	{
		return this.listener;
	}
	
	/**
	 * Sets the listener for this message
	 * @param listener the listener
	 */
	public final void setListener(ReplyListener listener)
	{
		this.listener = listener;
	}
	
	/**
	 * Returns the body of this message
	 * @return the body of this message
	 */
	public final ByteBuffer getBody()
	{
		return this.body;
	}
	
	/**
	 * Sets the body of this message
	 * @param body the new body
	 */
	public final void setBody(ByteBuffer body)
	{
		if (!this.isMutable) throw new IllegalStateException("Message is not mutable");
		if (body == null) this.body = emptyBody;
		this.body = body;
	}
	
	/**
	 * Gets the properties of this message
	 * @return the properties of this message
	 */
	public final Map<String, String> getProperties()
	{
		return this.isMutable ? this.properties : Collections.unmodifiableMap(this.properties);
	}
	
	/**
	 * Gets the value of the specified property
	 * @param property the name of the property
	 * @return the value of the property
	 */
	public final String getProperty(String property)
	{
		return this.properties.get(property);
	}
	
	/**
	 * Sets the value of the specified property
	 * @param property the name of the property
	 * @param value the value of the property
	 */
	public final void setProperty(String property, String value)
	{
		if (!this.isMutable)  throw new RuntimeException("Message is not mutable");
		if (property == null) throw new NullPointerException("Property name is null");
		if (value == null)    throw new NullPointerException("Property value is null");
		this.properties.put(property, value);
	}
	
	/**
	 * Checks if the message contains the specified property
	 * @param property the name of the property
	 * @return true if the message contains the property
	 */
	public final boolean hasProperty(String property)
	{
		return this.properties.containsKey(property);
	}
	
	/**
	 * Removes the specified property
	 * @param property the name of the property
	 */
	public final void removeProperty(String property)
	{
		if (!this.isMutable) throw new RuntimeException("Message is not mutable");
		this.properties.remove(property);
	}
	
	/**
	 * Clears all properties
	 */
	public final void clearProperties()
	{
		if (!this.isMutable) throw new RuntimeException("Message is not mutable");
		this.properties.clear();
	}
	
	/**
	 * Copies all properties from another message
	 * @param msg the message whose properties will be copied
	 */
	public final void copyProperties(Message msg)
	{
		if (!this.isMutable) throw new RuntimeException("Message is not mutable");
		this.properties.putAll(msg.properties);
	}
	
	/**
	 * Gets the value of the "Content-Type" property
	 * @return the value of the property
	 */
	public final String getContentType()
	{
		return this.properties.get("Content-Type");
	}
	
	/**
	 * Sets the value of the "Content-Type" property
	 * @param contentType the value of the property
	 */
	public final void setContentType(String contentType)
	{
		this.properties.put("Content-Type", contentType);
	}
	
	/**
	 * Gets the value of the "Profile" property
	 * @return the value of the property
	 */
	public final String getProfile()
	{
		return this.properties.get("Profile");
	}
	
	/**
	 * Sets the value of the "Profile" property
	 * @param profile the value of the property
	 */
	public final void setProfile(String profile)
	{
		this.properties.put("Profile", profile);
	}
	
	/**
	 * Gets the value of the "Error-Domain" property
	 * @return the error domain
	 */
	public final String getErrorDomain()
	{
		return this.properties.get("Error-Domain");
	}
	
	/**
	 * Sets the value of the "Error-Domain" property
	 * @param domain the error domain
	 */
	public final void setErrorDomain(String domain)
	{
		this.properties.put("Error-Domain", domain);
	}
	
	/**
	 * Gets the value of the "Error-Code" property
	 * @return the error code
	 */
	public final int getErrorCode() throws NumberFormatException
	{
		String code = this.properties.get("Error-Code");
		return Integer.parseInt(code);
	}
	
	/**
	 * Sets the value of the "Error-Code" property
	 * @param code the error code
	 */
	public final void setErrorCode(int code)
	{
		String err = Integer.toString(code);
		this.properties.put("Error-Code", err);
	}
	
	
	/**
	 * Returns a hash code for this message
	 * @return this message's hash code
	 */
	@Override
	public final int hashCode()
	{
		return this.number ^ this.connection.hashCode();
	}
	
	/**
	 * Compares this message with another object for equality
	 * @param object the object to compare this message to
	 * @return true if this message is equal to the specified object
	 */
	@Override
	public final boolean equals(Object that)
	{
		return (that instanceof Message) && this.equals((Message)that);
	}
	
	/**
	 * Compares this message with another message for equality
	 * @param message the message to compare this message to
	 * @return true if this message is equal to the specified message
	 */
	public final boolean equals(Message that)
	{
		return (this.number == that.number) && (this.connection == that.connection);
	}
	
	/**
	 * Compares this message with another message for ordering
	 * @param message the message to compare this message to
	 * @return a negative integer, zero, or a positive integer if this message's place is less than, equal, or greater than the specified message
	 */
	@Override
	public final int compareTo(Message that)
	{
		if (this.connection == that.connection)
			return this.number - that.number;
		else
			return 0; // FIXME
	}	
	
	/**
	 * Returns a string describing this message
	 * @return this message's string representation
	 */
	@Override
	public final String toString()
	{
		StringBuilder sb = new StringBuilder(String.format("%s[#%d%s, %d bytes",
						   this.getTypeName(), //this.getClass().getSimpleName(),
						   this.number,
						   this.isMine ? "->" : "<-",
						   this.body.limit()));
		
		if ((this.flags & COMPRESSED) != 0)
		{
			sb.append(", gzipped");
		}
		if ((this.flags & URGENT) != 0)
			sb.append(", urgent");
		if ((this.flags & NOREPLY) != 0)
			sb.append(", noreply");
		if ((this.flags & META) != 0)
			sb.append(", META");
		if ((this.flags & MORECOMING) != 0)
			sb.append(", incomplete");
		
		sb.append(']');
		return sb.toString();
	}
	
	/**
	 * Returns a string describing this message, containing a list of its property key-value pairs
	 * @return the string representation
	 */
	public final String toStringWithProperties()
	{
		StringBuilder sb = new StringBuilder(this.toString());
		sb.append(" {");
		for (Iterator<Map.Entry<String, String>> iter = this.properties.entrySet().iterator();;)
		{
			Map.Entry<String, String> entry = iter.next();
			sb.append(entry.getKey());
			sb.append(':');
			sb.append(entry.getValue());
			if (!iter.hasNext()) break;
			sb.append(", ");
		}
		sb.append('}');
		return sb.toString();
	}
}

final class ByteBufferInputStream extends InputStream
{
	private final ByteBuffer buffer;
	
	public ByteBufferInputStream(ByteBuffer buffer)
	{
		this.buffer = buffer;
	}
	
	@Override
	public int read() throws IOException
	{
		return (this.buffer.get() & 0xFF);
	}
}

final class ByteBufferOutputStream extends OutputStream
{
	private final ByteBuffer buffer;
	
	public ByteBufferOutputStream(ByteBuffer buffer)
	{
		this.buffer = buffer;
	}

	@Override
	public void write(int b) throws IOException
	{
		this.buffer.put((byte)(b & 0xFF));
	}
}