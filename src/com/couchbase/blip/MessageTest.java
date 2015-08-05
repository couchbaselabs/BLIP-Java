package com.couchbase.blip;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import javax.xml.bind.DatatypeConverter;

public class MessageTest
{
	private static void printByteBuffer(ByteBuffer buffer)
	{
		if (buffer == null)
		{
			System.out.println("null");
		}
		else
		{
			byte[] data = new byte[buffer.limit()];
			buffer.rewind();
			buffer.get(data);
			//System.out.println(DatatypeConverter.printHexBinary(data));
			for (byte b : data)
			{
				if (b >= 0x20 && b < 0x7F) System.out.print((char)b);
				else  System.out.print("[" + Integer.toHexString((int)(b & 0xFF)) + ']');
			}
			System.out.print(" (" + buffer.limit() + " bytes)");
			System.out.println();
		}
	}
	
	/*
	@Test
	public void testEncodeAndDecode()
	{
		Message m1 = new Message();
		m1.isMutable = true;
		m1.isMine = true;
		m1.number = 297;
		m1.body = ByteBuffer.allocate(48).put(8, (byte)'%');
		m1.setProfile("Test profile");
		m1.setProperty("Test key", "Test value");
		m1.setProperty("Content-Type", "application/json");
		ByteBuffer b1 = m1.makeNextFrame(16);
		ByteBuffer b2 = m1.makeNextFrame(16);
		ByteBuffer b3 = m1.makeNextFrame(256);
		//printByteBuffer(b1);
		//printByteBuffer(b2);
		//printByteBuffer(b3);
		
		Message m2 = new Message();
		m2.isMine = true;
		m2.readFirstFrame(b1);
		m2.readNextFrame(b2);
		m2.readNextFrame(b3);
		assertEquals(m2.getNumber(), 297);
		assertEquals(m2.getProfile(), "Test profile");
		assertEquals(m2.getProperty("Test key"), "Test value");
		assertEquals(m2.getProperty("Content-Type"), "application/json");
		assertEquals((char)m2.getBody().get(8), '%');
		assertEquals(m1, m2);
	}
	*/
}
