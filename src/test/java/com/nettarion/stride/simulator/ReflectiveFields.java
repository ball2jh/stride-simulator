package com.nettarion.stride.simulator;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reflection helpers shared by the tests that walk every public field of a state class: they set each
 * field to a distinct raw pattern so a field dropped from copy, equality, digest or wire plumbing fails.
 */
final class ReflectiveFields {
	private ReflectiveFields() {}

	/** Whether {@code field} is a public instance field, the kind the state plumbing must carry. */
	static boolean isInstanceField(final Field field) {
		return !Modifier.isStatic(field.getModifiers());
	}

	/**
	 * Sets {@code field} on {@code target} to a distinct raw pattern keyed on {@code ordinal}: NaN
	 * payloads for the floating-point types, so a field that reaches its consumer only through a lossy
	 * conversion still fails, and an enum constant chosen by ordinal.
	 *
	 * @throws AssertionError for a field type this helper does not cover, so a new type is added here
	 *     rather than slipping past every guard
	 */
	static void fill(final Field field, final Object target, final int ordinal) throws IllegalAccessException {
		Class<?> type = field.getType();
		if (type == double.class) {
			field.setDouble(target, Double.longBitsToDouble(0x7ff8_0000_0000_0000L + ordinal));
		} else if (type == float.class) {
			field.setFloat(target, Float.intBitsToFloat(0x7fc0_0000 + ordinal));
		} else if (type == boolean.class) {
			field.setBoolean(target, (ordinal & 1) != 0);
		} else if (type == int.class) {
			field.setInt(target, 0x1357_0000 + ordinal);
		} else if (type == long.class) {
			field.setLong(target, 0x1357_0000_0000L + ordinal);
		} else if (type == byte.class) {
			field.setByte(target, (byte) (0x40 + ordinal));
		} else if (type.isEnum()) {
			Object[] constants = type.getEnumConstants();
			field.set(target, constants[ordinal % constants.length]);
		} else {
			throw new AssertionError("unhandled field type " + type + " for " + field.getName()
			    + "; extend ReflectiveFields rather than letting a field slip past the guards");
		}
	}

	/**
	 * Changes {@code field} on {@code target} to a value that differs from its current one in at least
	 * one raw bit: the boolean is inverted, the numbers are XORed with a pattern keyed on
	 * {@code ordinal}, and an enum moves to another constant.
	 */
	static void mutate(final Field field, final Object target, final int ordinal) throws IllegalAccessException {
		Class<?> type = field.getType();
		if (type == double.class) {
			field.setDouble(target, Double.longBitsToDouble(0x7ff8_0000_0000_0000L + ordinal));
		} else if (type == float.class) {
			field.setFloat(target, Float.intBitsToFloat(0x7fc0_0000 + ordinal));
		} else if (type == boolean.class) {
			field.setBoolean(target, !field.getBoolean(target));
		} else if (type == int.class) {
			field.setInt(target, field.getInt(target) ^ (0x1357_0000 + ordinal));
		} else if (type == byte.class) {
			field.setByte(target, (byte) (field.getByte(target) ^ (0x40 + ordinal)));
		} else if (type == long.class) {
			field.setLong(target, field.getLong(target) ^ (0x1357_0000_0000L + ordinal));
		} else if (type.isEnum()) {
			Object[] constants = type.getEnumConstants();
			field.set(target, field.get(target) == constants[0] ? constants[1] : constants[0]);
		} else {
			throw new AssertionError("unhandled field type " + type + " for " + field.getName()
			    + "; extend ReflectiveFields rather than letting a field slip past the guards");
		}
	}

	/** The raw bits of {@code field} on {@code target}: raw long bits for doubles, raw int bits for floats. */
	static Object rawValue(final Field field, final Object target) throws IllegalAccessException {
		Class<?> type = field.getType();
		if (type == double.class) {
			return Double.doubleToRawLongBits(field.getDouble(target));
		}
		if (type == float.class) {
			return Float.floatToRawIntBits(field.getFloat(target));
		}
		return field.get(target);
	}
}
