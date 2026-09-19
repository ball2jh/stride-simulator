package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * One Minecraft Java Edition 26.2 client movement tick inside the admitted domain, without
 * Minecraft.
 *
 * <p>When {@link #tick} returns normally, the supplied state holds the raw movement successor
 * (collision, support, fluid, pose, and retained input facts) that vanilla's client tick produces
 * for the same admitted state, action, and complete world facts, to the bit. The claim excludes
 * server correction, damage, interactions, world mutation, and any context the tick refuses.
 *
 * <p>State admission. The caller supplies a client state that is:
 * <ul>
 * <li>alive, unmounted, non-spectator, with ordinary collision enabled;</li>
 * <li>on default movement attributes except the modeled sprint and frost modifiers and the
 * admitted Speed, Slowness, and Jump Boost inputs;</li>
 * <li>free of Blindness, Dolphin's Grace, Depth Strider, and item-use slowdown;</li>
 * <li>subject to no entity or world-border collision;</li>
 * <li>structurally valid: an imported state has passed
 * {@link PlayerState#requireValidForTransition()}, which the tick does not repeat;</li>
 * <li>centered inside {@link PlayerState#supportsCenterPosition(double, double, double)} before and
 * after the tick; the box may extend past that domain.</li>
 * </ul>
 * The {@link WorldView} must declare {@code movementFactsComplete()}.
 *
 * <p>The state is mutated in place. A malformed call fails with an argument or state exception; a
 * well-formed call that leaves the admitted domain fails with {@link UnimplementedMechanicException}
 * and is never clamped or defaulted. Refusal can be discovered after mutation begins, so discard
 * the state after any exception, or keep a copy.
 *
 * <p>Threading: the no-scratch overload uses a {@link Scratch} held in a {@code ThreadLocal}, so one
 * instance may be shared across threads; batch callers keep one {@link Scratch} per worker and use
 * the other overload to skip the lookup. The instance itself holds no state.
 */
public final class ClientTick {
	private static final ThreadLocal<Scratch> THREAD_SCRATCH = ThreadLocal.withInitial(Scratch::new);

	/** A stateless client tick; every instance is equivalent. */
	public ClientTick() {}

	/**
	 * Mutates {@code state} through one complete admitted client movement tick, using the calling
	 * thread's scratch.
	 *
	 * <p>This is the physics alone. What the tick publishes to the server is the root package's
	 * {@code Publisher}'s selection on the result, which the composed step in {@code Simulator} runs
	 * after it.
	 *
	 * @throws NullPointerException when a required argument is {@code null}
	 * @throws UnimplementedMechanicException when the transition reaches an unadmitted state,
	 *         coordinate, mechanic, or world fact
	 */
	public void tick(final PlayerState state, final PlayerInput action, final WorldView world) {
		tick(state, action, world, THREAD_SCRATCH.get());
	}

	/**
	 * The same tick with a caller-owned {@link Scratch}, so a batch stepper allocates nothing per
	 * tick. The scratch must carry client authority.
	 *
	 * @throws NullPointerException when a required argument is {@code null}
	 * @throws IllegalStateException when {@code scratch} carries server authority
	 * @throws UnimplementedMechanicException when the transition reaches an unadmitted state,
	 *         coordinate, mechanic, or world fact
	 */
	public void tick(final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		scratch.authority.requireClient();
		PlayerTick.tick(state, action, world, scratch);
	}
}
