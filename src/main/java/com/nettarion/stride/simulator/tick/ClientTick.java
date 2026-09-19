package com.nettarion.stride.simulator.tick;

import com.nettarion.stride.simulator.AABB;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.world.WorldView;

/**
 * Performs one exact Minecraft 26.2 client movement tick within the admitted
 * mechanic slice, without Minecraft.
 *
 * <p><strong>Exactness.</strong> When {@link #tick} returns normally, the
 * supplied state contains the raw movement successor (including collision,
 * support, fluid, pose, and retained input facts) produced by the pinned client
 * transition for the same admitted state, action, and complete world facts.
 * Numeric widths, conversion points, and operation order are part of that
 * claim. The claim does not include server correction, damage or health,
 * interactions, world mutation, or any context the kernel explicitly refuses.
 *
 * <p><strong>State admission.</strong> The caller must supply a locally
 * authoritative, alive, unmounted,
 * non-spectator player state with ordinary collision enabled, default movement
 * attributes except the modelled sprint/frost modifiers and supported Speed,
 * Slowness, and Jump Boost inputs, no Blindness, Dolphin's Grace or Depth
 * Strider, no item-use slowdown, and no external entity
 * or border collision.
 * Imported state must first pass {@link PlayerState#requireValidForTransition()}.
 * Its initial and successor centers must remain inside the finite coordinate
 * domain declared by {@link PlayerState#supportsCenterPosition(double, double,
 * double)}; collision boxes may extend past their centers. Live capture enforces
 * the remaining admission rules.
 *
 * <p><strong>World admission.</strong> A {@link WorldView} must explicitly
 * declare the transition's movement-fact closure complete. That closure and its
 * versioning rules are the contract on {@code WorldView}; a neutral default is
 * usable only when the implementation knows the corresponding fact is absent.
 *
 * <p><strong>Caller-visible outcomes.</strong> The input state is mutated in
 * place and is the only successful result. A malformed call fails with an
 * argument or state exception. An otherwise well-formed call that leaves the
 * admitted slice fails with {@link com.nettarion.stride.simulator.UnimplementedMechanicException}; it is never
 * clamped or continued with an ordinary/default answer. Because refusal can be
 * discovered after mutation begins, callers must discard the state after any
 * exception (or retain a copy before calling).
 *
 * <p>Callers that need allocation-free search or batch execution may retain one
 * {@link Scratch} per thread; ordinary callers can use the overload that
 * owns its scratch internally.
 *
 * <p>Operation order, numeric widths, and float/double conversion points are
 * observable mechanics. The tick is the sequence of vanilla's phases, each
 * one class with a stated write set: {@link BaseTick} (what the player is
 * immersed in), {@link AiStep} (the control decisions through the jump),
 * {@link Travel} (velocity from the medium, running {@link Move} for the
 * displacement), {@link com.nettarion.stride.simulator.block.BlockEffects} (the block bodies the movement
 * reached, dispatched per palette entry through {@link com.nettarion.stride.simulator.block.BlockBehaviour}),
 * {@link Freezing} (the server's freezing tail), then
 * {@code LocalPlayer.aiStep}'s tail and {@link PlayerPose}. The same
 * sequence ticks the server's copy under server authority, where each
 * phase's server-only branches are live and deal their hits through
 * {@link com.nettarion.stride.simulator.server.TickAuthority#survival()}. The queries
 * the phases share are {@link Clearance}, {@link SupportingBlock}, and
 * {@link AABB}; the player's own predicates ({@link PlayerTick#isShiftKeyDown},
 * {@link PlayerTick#hasInput}, {@link PlayerTick#onClimbable}) belong to {@link PlayerTick}.
 */
public final class ClientTick {
	private static final ThreadLocal<Scratch> THREAD_SCRATCH = ThreadLocal.withInitial(Scratch::new);

	public ClientTick() {}

	/**
	 * Mutate {@code state} through one complete admitted client movement tick.
	 *
	 * <p>This is the physics alone. What the tick publishes to the server is
	 * the {@link com.nettarion.stride.simulator.Publisher}'s selection on the result, which the composed
	 * step in {@link com.nettarion.stride.simulator.Simulator} runs after it.
	 *
	 * @throws NullPointerException when a required argument is {@code null}
	 * @throws com.nettarion.stride.simulator.UnimplementedMechanicException when the transition reaches an
	 *         unadmitted state, coordinate, mechanic, or world fact
	 */
	public void tick(final PlayerState state, final PlayerInput action, final WorldView world) {
		tick(state, action, world, THREAD_SCRATCH.get());
	}

	/**
	 * Allocation-free transition using scratch storage owned by the calling
	 * thread. Batch steppers should retain one instance per worker.
	 *
	 * @throws NullPointerException when a required argument is {@code null}
	 * @throws com.nettarion.stride.simulator.UnimplementedMechanicException when the transition reaches an
	 *         unadmitted state, coordinate, mechanic, or world fact
	 */
	public void tick(final PlayerState state, final PlayerInput action, final WorldView world, final Scratch scratch) {
		scratch.authority.requireClient();
		PlayerTick.tick(state, action, world, scratch);
	}
}
