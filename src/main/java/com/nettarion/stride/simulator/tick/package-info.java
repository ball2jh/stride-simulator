/**
 * The player tick as a phase pipeline: the transcription of Entity, LivingEntity, Player and
 * LocalPlayer, shared by the client's copy and the server's copy of the player.
 *
 * <p>{@link com.nettarion.stride.simulator.tick.ClientTick} is the standalone client transition.
 * {@link com.nettarion.stride.simulator.tick.PlayerTick} sequences the phases:
 * {@link com.nettarion.stride.simulator.tick.BaseTick}, {@link com.nettarion.stride.simulator.tick.AiStep},
 * {@link com.nettarion.stride.simulator.tick.Travel}, {@link com.nettarion.stride.simulator.tick.Move},
 * {@link com.nettarion.stride.simulator.tick.PlayerPose}, {@link com.nettarion.stride.simulator.tick.Freezing},
 * then the block effects. {@link com.nettarion.stride.simulator.tick.EntityFluidInteraction},
 * {@link com.nettarion.stride.simulator.tick.Clearance} and
 * {@link com.nettarion.stride.simulator.tick.SupportingBlock} are the fluid, pose-fit and support
 * queries those phases share. {@link com.nettarion.stride.simulator.tick.Scratch} is the per-thread
 * buffer set; its caches are not semantic state and invalidate with the world and state they read.
 *
 * <p>The arithmetic and its order are a transcription of the pinned source and stay one. Static
 * composition replaces the game's inheritance graph, and flat state replaces its objects; those
 * are the measured performance choices this package keeps. Server authority questions go through
 * the server package's {@code TickAuthority}; this package sends no packets and decides no damage.
 */
package com.nettarion.stride.simulator.tick;
