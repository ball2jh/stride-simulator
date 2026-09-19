package com.nettarion.stride.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class PlayerStateCopyTest {
	@Test
	void copyIntoCarriesEveryPublicStateFieldRawBitExactly() throws IllegalAccessException {
		PlayerState source = new PlayerState();
		int ordinal = 1;
		for (Field field : PlayerState.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			Class<?> type = field.getType();
			if (type == double.class) {
				field.setDouble(source, Double.longBitsToDouble(0x7ff8_0000_0000_0000L + ordinal));
			} else if (type == float.class) {
				field.setFloat(source, Float.intBitsToFloat(0x7fc0_0000 + ordinal));
			} else if (type == boolean.class) {
				field.setBoolean(source, (ordinal & 1) != 0);
			} else if (type == int.class) {
				field.setInt(source, 0x1357_0000 + ordinal);
			} else if (type == byte.class) {
				field.setByte(source, (byte) (0x40 + ordinal));
			} else if (type == PlayerState.Pose.class) {
				field.set(source, PlayerState.Pose.SWIMMING);
			}
			ordinal++;
		}
		PlayerState copy = new PlayerState();

		source.copyInto(copy);

		for (Field field : PlayerState.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			Class<?> type = field.getType();
			if (type == double.class) {
				assertEquals(Double.doubleToRawLongBits(field.getDouble(source)),
				    Double.doubleToRawLongBits(field.getDouble(copy)), field.getName());
			} else if (type == float.class) {
				assertEquals(Float.floatToRawIntBits(field.getFloat(source)),
				    Float.floatToRawIntBits(field.getFloat(copy)), field.getName());
			} else {
				assertEquals(field.get(source), field.get(copy), field.getName());
			}
		}
	}
}
