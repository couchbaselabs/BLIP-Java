package com.couchbase.blip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
 * @see <a href="https://github.com/couchbaselabs/BLIP-Cocoa/blob/master/Docs/BLIP%20Protocol%20v1.md">The BLIP Protocol</a>, {@link Connection}
 */
public final class Message
{
	// Constant values used in message encoding/decoding and transport:
	
	// The magic number at the start of a frame header
	public static final int FRAME_MAGIC          = 0x9B34F206;
	
	// Message types
	public static final int MSG                  = 0x00;
	public static final int RPY                  = 0x01;
	public static final int ERR                  = 0x02;
	public static final int ACKMSG               = 0x04;
	public static final int ACKRPY               = 0x05;
	
	public static final int TYPE_MASK            = 0x07;
	
	// Frame flags
	public static final int COMPRESSED           = 0x08;
	public static final int URGENT               = 0x10;
	public static final int NOREPLY              = 0x20;
	public static final int MORECOMING           = 0x40;
	public static final int META                 = 0x80;
	
	public static final int MAX_FLAG             = 0xFF;
	
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
	
	
	// Encoder for converting Java strings to C strings
	private static final CharsetEncoder cStringEncoder = Charset.forName("ISO-8859-1").newEncoder();
	
	
	protected Connection                connection;
	
	protected int                       number;

	protected final Map<String, String> properties = new HashMap<String, String>();
	protected byte[]                    body;
	protected int                       flags;
	
	protected boolean                   isMine;
	protected boolean					isMutable;
	protected boolean                   isCompressed;
	protected boolean                   isUrgent;
	
	
	Message() {}
	
	
	// Somewhat hack-ish solution to decoder methods not being able to return both a value and a length
	private transient int decoderOffset;	
	
	
	// Converts a variable-length integer (varint) to a 32-bit integer
	private int readVarint(byte[] data)
	{
		int result = 0;
		int offset = this.decoderOffset;
		int i;
		             result  =  (i = data[offset++]) & 127;
		if (i > 127) result &= ((i = data[offset++]) & 127) << 7;
		if (i > 127) result &= ((i = data[offset++]) & 127) << 14;
		if (i > 127) result &= ((i = data[offset++]) & 127) << 21;
		if (i > 127) result &= ((i = data[offset++]) & 127) << 28;
		if (i > 127) throw new RuntimeException("Varint is too large");
		this.decoderOffset = offset;
		return result;
	}
	
	// Converts a 32-bit integer to a variable-length integer (varint)
	private static byte[] makeVarint(int value)
	{
		byte[] result;
		if ((value & 0x7f) == value)
		{
			result = new byte[]{ (byte)(value) };
		}
		else if ((value & 0x3fff) == value)
		{
			result = new byte[]{ (byte)(value & 0x80), (byte)(value >> 7) };
		}
		else if ((value & 0x1ffff) == value)
		{
			result = new byte[]{ (byte)(value & 0x80), (byte)((value >> 7) & 0x80), (byte)(value >> 14) };
		}
		else if ((value & 0xffffff) == value)
		{
			result = new byte[]{ (byte)(value & 0x80), (byte)((value >> 7) & 0x80), (byte)((value >> 14) & 0x80), (byte)(value >> 21) };
		}
		else if ((value & 0x7fffffff) == value)
		{
			result = new byte[]{ (byte)(value & 0x80), (byte)((value >> 7) & 0x80), (byte)((value >> 14) & 0x80), (byte)((value >> 21) & 0x80), (byte)(value >> 28) };
		}
		else
		{
			result = new byte[]{ (byte)(value & 0x80), (byte)((value >> 7) & 0x80), (byte)((value >> 14) & 0x80), (byte)((value >> 21) & 0x80), (byte)((value >> 28) & 0x80), (byte)1 };
		}
		return result;
	}
	
	
	// Converts a null-terminated UTF-8 string to a Java string
	private String readCString(byte[] data)
	{
		int offset = this.decoderOffset, i = offset;
		String result;
		if (data[i] == 0)
		{
			result = "";
		}
		else if (data[i] < 31 && data[i] < commonProperties.length && data[i + 1] == 0)
		{
			result = commonProperties[data[i++] - 1];
		}
		else
		{
			while (data[++i] != 0);
			result = new String(data, offset, i - offset);
		}
		this.decoderOffset = i + 1;
		return result;
	}
	
	// Converts a Java string to a null-terminated UTF-8 string
	private static byte[] makeCString(String string)
	{
		Byte b = propertyAbbreviations.get(string);
		if (b == null)
		{
			int len = string.length();
			byte[] val = new byte[len + 1];
			ByteBuffer bbuf = ByteBuffer.wrap(val);
			cStringEncoder.encode(CharBuffer.wrap(string), bbuf, true);
			val[len] = 0;
			return val;
		}
		else
		{
			return new byte[]{b, 0x00};
		}
	}
	
	// Compresses a message's body
	private static byte[] compress(byte[] data) throws IOException
	{
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		GZIPOutputStream gzs = new GZIPOutputStream(os);
		gzs.write(data);
		gzs.close();
		return os.toByteArray();
	}
	
	// Decompresses a message's body
	private static byte[] decompress(byte[] data) throws IOException
	{
		GZIPInputStream gzs = new GZIPInputStream(new ByteArrayInputStream(data));
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while ((len = gzs.read(buf, 0, buf.length)) != -1)
		{
			os.write(buf, 0, len);
		}
		byte[] result = os.toByteArray();
		gzs.close();
		os.close();
		return result;
	}
	
	
	final void decode(byte[] data) 
	{
		this.decoderOffset = 0;
		int propertiesEnd = readVarint(data) + this.decoderOffset;
		if (data.length < propertiesEnd)  throw new RuntimeException("Properties block size out of bounds");
		if (data[propertiesEnd - 1] != 0) throw new RuntimeException("Malformed properties block");
		while (this.decoderOffset != propertiesEnd)
		{
			String key   = this.readCString(data);
			if (this.decoderOffset == propertiesEnd) throw new RuntimeException("Malformed properties block");
			String value = this.readCString(data);
			this.properties.put(key, value);
		}		
		byte[] body = new byte[data.length - this.decoderOffset];
		System.arraycopy(data, this.decoderOffset, body, 0, body.length);
		this.body = body;
	}
	
	// TODO Probably pretty inefficient, work more on this later
	final byte[] encode()
	{
		if (!this.isMine) throw new UnsupportedOperationException("Cannot encode a message that is not mine");
		
		int propertiesSize = 0;
		byte[][] propertyCStrings = new byte[this.properties.size() * 2][];
		int iter = 0;
		for (Map.Entry<String, String> entry : this.properties.entrySet())
		{
			byte[] key = makeCString(entry.getKey());
			propertiesSize += key.length;
			propertyCStrings[iter++] = key;
			byte[] value = makeCString(entry.getValue());
			propertiesSize += value.length;
			propertyCStrings[iter++] = value;
		}
		byte[] sizeVarint = makeVarint(propertiesSize);
		
		//debug
		//System.out.println("Varint conversion: (" + Integer.toHexString(propertiesSize) + ") to [" + DatatypeConverter.printHexBinary(sizeVarint) + "]");
		
		byte[] data = new byte[sizeVarint.length + propertiesSize + this.body.length];
		System.arraycopy(sizeVarint, 0, data, 0, iter = sizeVarint.length);
		for (byte[] b : propertyCStrings)
		{
			System.arraycopy(b, 0, data, iter, b.length);
			iter += b.length;
		}
		System.arraycopy(this.body, 0, data, iter, this.body.length);
		
		//debug
		//System.out.println("Data output: [" + DatatypeConverter.printHexBinary(data) + "]");
		
		return data;
	}
	
	
	// Testing method for SimpleWebSocketConnection
	final void decodeFromSingleFrame(ByteBuffer frame)
	{	
		frame.rewind();	
		
		if (frame.getInt() != FRAME_MAGIC) throw new RuntimeException("Corrupted or invalid frame");
		
		this.number = frame.getInt();
		frame.getShort();
		int size = frame.getShort() - 12;
		
		byte[] data = new byte[size];
		frame.get(data, 0, size);
		
		this.decode(data);
	}
	
	// Testing method for SimpleWebSocketConnection
	final ByteBuffer encodeAsSingleFrame()
	{
		byte[] encoding = this.encode();
		ByteBuffer frame = ByteBuffer.allocate(encoding.length + 12);
		frame.putInt(FRAME_MAGIC);
		frame.putInt(this.number);
		frame.putShort((short)this.flags);
		frame.putShort((short)(encoding.length + 12));
		frame.put(encoding);
		frame.rewind();
		return frame;
	}
	
	// The first frame contains the message header and properties
	final ByteBuffer makeFirstFrame()
	{
		
		return null;
	}
	
	final ByteBuffer makeNextFrame(int maxLength)
	{
		
		return null;
	}
	
	final void readNextFrame(ByteBuffer frame)
	{
		
	}
	
	
	/**
	 * Sends this message over its associated connection, if it's an outgoing request
	 * @return the response this message receives, or null if it has the NOREPLY flag set
	 */
	public final Message send()
	{
		return this.connection.sendRequest(this);
	}
	
	/**
	 * Sends a binary reply to this message, if it's an incoming request
	 * @param body the body of the reply
	 */
	public final void reply(ByteBuffer body)
	{
		Message msg = new Message();
		
	}
	
	/**
	 * Sends a string reply to this message, if it's an incoming request
	 * @param body the body of the reply
	 * @throws NullPointerException if the body string is null
	 */
	public final void reply(String body)
	{
		if (body == null) throw new NullPointerException("Body is null");
		
		Message msg = new Message();
		msg.body = body.getBytes();
		
		
	}
	
	/**
	 * Sends a reply to this message containing the specified properties,
	 * if it's an incoming request
	 * @param properties a map of properties 
	 */
	public final void reply(Map<String, String> properties)
	{
		if (properties == null) throw new NullPointerException("Properties are null");
		
		Message msg = new Message();
		msg.properties.putAll(properties);
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
	 * Returns the BLIP connection that created or received this message
	 * @return the Message's BLIP connection
	 */
	public final Connection getConnection()
	{
		return this.connection;
	}
	
	/**
	 * Returns the body of this message
	 * @return the body of this message
	 */
	public final byte[] getBody()
	{
		return this.body;
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
	 * Removes the specified property
	 * @param property the name of the property
	 */
	public final void removeProperty(String property)
	{
		if (!this.isMutable) throw new RuntimeException("Message is not mutable");
		this.properties.remove(property);
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
	 * Returns a string representation of this message
	 * @return the message's string representation
	 */
	@Override
	public final String toString()
	{
		StringBuilder sb = new StringBuilder(String.format("%s[#%d%s, %d bytes",
						   this.getClass().getSimpleName(),
						   this.number,
						   this.isMine ? "->" : "<-",
						   this.body.length));
		
		if ((this.flags & COMPRESSED) != 0)
		{
			
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
	 * Returns a string representation of this message, containing a list of its property key-value pairs
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
	
	@Override
	public final int hashCode()
	{
		return super.hashCode();
	}
	
	@Override
	public final boolean equals(Object obj)
	{
		return super.equals(obj);
	}
}
