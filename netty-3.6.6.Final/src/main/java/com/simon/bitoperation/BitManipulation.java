package com.simon.bitoperation;

//: BitManipulation.java
//Using the bitwise operators
import java.util.Random;

public class BitManipulation {
	public static void main(String[] args) {
		Random rand = new Random();
		int i = rand.nextInt();
		int j = rand.nextInt();
		pBinInt("-1", -1);
		pBinInt("+1", +1);
		int maxpos = 2147483647;
		pBinInt("maxpos", maxpos);
		int maxneg = -2147483648;
		pBinInt("maxneg", maxneg);
		pBinInt("i", i);
		pBinInt("~i", ~i);
		pBinInt("-i", -i);
		pBinInt("j", j);
		pBinInt("i & j", i & j);
		pBinInt("i | j", i | j);
		pBinInt("i ^ j", i ^ j);
		pBinInt("i << 5", i << 5);
		pBinInt("i >> 5", i >> 5);
		pBinInt("(~i) >> 5", (~i) >> 5);
		pBinInt("i >>> 5", i >>> 5);
		pBinInt("(~i) >>> 5", (~i) >>> 5);
		
		pBinInt("255", 255);
		pBinInt("255 >> 5", 255 >> 5);
		pBinInt("-256", -256);
		pBinInt("-256 >> 5", (-256) >> 5);
		pBinInt("-256 >> 8", (-256) >> 8);
		
		long l = rand.nextLong();
		long m = rand.nextLong();
		pBinLong("-1L", -1L);
		pBinLong("+1L", +1L);
		long ll = 9223372036854775807L;
		pBinLong("maxpos", ll);
		long lln = -9223372036854775808L;
		pBinLong("maxneg", lln);
		pBinLong("l", l);
		pBinLong("~l", ~l);
		pBinLong("-l", -l);
		pBinLong("m", m);
		pBinLong("l & m", l & m);
		pBinLong("l | m", l | m);
		pBinLong("l ^ m", l ^ m);
		pBinLong("l << 5", l << 5);
		pBinLong("l >> 5", l >> 5);
		pBinLong("(~l) >> 5", (~l) >> 5);
		pBinLong("l >>> 5", l >>> 5);
		pBinLong("(~l) >>> 5", (~l) >>> 5);
	}

	static void pBinInt(String s, int i) {
		System.out.println(s + ", int: " + i + ", binary: ");
		System.out.print(" ");
		for (int j = 31; j >= 0; j--)
			if (((1 << j) & i) != 0)
				System.out.print("1");
			else
				System.out.print("0");
		System.out.println();
	}

	static void pBinLong(String s, long l) {
		System.out.println(s + ", long: " + l + ", binary: ");
		System.out.print(" ");
		for (int i = 63; i >= 0; i--)
			if (((1L << i) & l) != 0)
				System.out.print("1");
			else
				System.out.print("0");
		System.out.println();
	}
} ///:~
