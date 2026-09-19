package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.Interaction;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.SimulationEvent;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A recorded {@link ScheduledSimulation}: the starting state and world, every event applied, and
 * the complete {@link SimulationCheckpoint} after each one, stored as one gzip file.
 *
 * <p>A recording is built either by appending events to a running simulation, which computes each
 * checkpoint, or from independently observed checkpoints through {@link #observed} and
 * {@link #appendObserved}. {@link #replay} re-runs every event and refuses at the first checkpoint
 * whose bytes differ, naming the field. The file layout is specified in
 * {@code docs/trace-formats.md}. Not thread-safe.
 *
 * <p>A recording is verified when it was made against a {@link BlockStateCatalog.Source}; the
 * catalog's fingerprint is stored and a later replay must supply a source producing the same
 * catalog. An unverified recording replays without a catalog and refuses one.
 */
public final class ScheduledRecording {
	/** The container format this library reads and writes; no other version is read. */
	public static final int FORMAT_VERSION = 3;

	static final String MAGIC = "stride-scheduled-recording";
	private static final String CLIENT_TICK_EVENT = "CLIENT_TICK";
	private static final String FINGERPRINT_ALGORITHM = "SHA-256";

	/** The most events a file may declare. */
	private static final int MAX_EVENTS = 1_000_000;

	/** The most bytes one embedded blob (checkpoint, world text, fingerprint) may occupy: 256 MiB. */
	private static final int MAX_BLOB_BYTES = 256 * 1024 * 1024;

	private final SimulationState initial;
	private final WorldSnapshot world;
	private final String version;
	private final Interaction interaction;
	private final boolean verified;
	private final byte[] catalogFingerprint;
	private final List<SimulationEvent> events = new ArrayList<>();
	private final List<byte[]> checkpoints = new ArrayList<>();
	private final List<TraceProducer> producers = new ArrayList<>();
	private ScheduledSimulation current;

	/**
	 * Starts recording a simulation at {@code initial}, a state with no packets in transit, and
	 * takes its first checkpoint. With a non-null {@code source} the recording is verified against
	 * the catalog it extracts for {@code world}; refuses a source for another Minecraft version.
	 */
	public ScheduledRecording(final SimulationState initial, final WorldSnapshot world, final String version,
	    final Interaction interaction, final BlockStateCatalog.Source source) {
		this(initial, world, version, interaction, source, true);
	}

	private ScheduledRecording(final SimulationState initial, final WorldSnapshot world, final String version,
	    final Interaction interaction, final BlockStateCatalog.Source source, final boolean computed) {
		this.initial = Objects.requireNonNull(initial, "initial");
		this.world = Objects.requireNonNull(world, "world");
		this.version = Objects.requireNonNull(version, "version");
		this.interaction = Objects.requireNonNull(interaction, "interaction");
		this.verified = source != null;
		if (source != null) {
			if (!source.minecraftVersion().equals(version)) {
				throw new IllegalArgumentException("block catalog source version mismatch");
			}
			this.catalogFingerprint = fingerprint(source.catalog(world));
		} else {
			this.catalogFingerprint = new byte[0];
		}
		if (computed) {
			this.current = create(source);
			this.checkpoints.add(SimulationCheckpoint.capture(this.current).encode());
			this.producers.add(TraceProducer.PURE_KERNEL);
		}
	}

	/**
	 * Starts a recording from an independently observed first checkpoint. No simulator phase or
	 * world compilation runs; {@link #replay} or {@link #resume} must be called before
	 * {@link #append} or {@link #forkCurrent}.
	 */
	public static ScheduledRecording observed(final SimulationState initial, final WorldSnapshot world,
	    final String version, final Interaction interaction, final BlockStateCatalog.Source source,
	    final SimulationCheckpoint.Checkpoint initialCheckpoint, final TraceProducer producer) {
		ScheduledRecording recording = new ScheduledRecording(initial, world, version, interaction, source, false);
		recording.checkpoints.add(initialCheckpoint.encode());
		recording.producers.add(Objects.requireNonNull(producer, "producer"));
		return recording;
	}

	private ScheduledSimulation create(final BlockStateCatalog.Source source) {
		SnapshotView view;
		if (this.verified) {
			if (source == null || !source.minecraftVersion().equals(this.version)) {
				throw new IllegalArgumentException(
				    "a verified recording needs the block catalog source it was made with");
			}
			BlockStateCatalog catalog = source.catalog(this.world);
			if (!Arrays.equals(this.catalogFingerprint, fingerprint(catalog))) {
				throw new IllegalArgumentException("block catalog differs from the one the recording was made with");
			}
			view = SnapshotView.compile(this.world, catalog, this.version);
		} else {
			if (source != null) {
				throw new IllegalArgumentException("an unverified recording replays without a block catalog");
			}
			view = SnapshotView.compile(this.world);
		}
		return new ScheduledSimulation(this.initial, view, this.interaction);
	}

	/** Replays the recorded prefix so that {@link #append} and {@link #forkCurrent} are available again. */
	public ScheduledRecording resume(final BlockStateCatalog.Source source) {
		this.current = replay(source);
		return this;
	}

	/**
	 * An independent fork of the simulation after the last event. Refuses with
	 * {@link IllegalStateException} when no simulation has been executed, as after
	 * {@link #observed}, {@link #appendObserved} or {@link #read}; call {@link #resume} first.
	 */
	public ScheduledSimulation forkCurrent() {
		if (this.current == null) {
			throw new IllegalStateException("recording has no executed simulation; call replay or resume first");
		}
		return this.current.fork();
	}

	/**
	 * A new recording of the same events with every checkpoint recomputed by this simulator.
	 * Refuses with {@link IllegalArgumentException} when any checkpoint was observed by another
	 * producer, since observations cannot be recomputed.
	 */
	public ScheduledRecording recompute(final BlockStateCatalog.Source source) {
		if (this.producers.stream().anyMatch(p -> p != TraceProducer.PURE_KERNEL)) {
			throw new IllegalArgumentException("observed checkpoints cannot be recomputed");
		}
		ScheduledRecording updated =
		    new ScheduledRecording(this.initial, this.world, this.version, this.interaction, source);
		for (SimulationEvent event : this.events) {
			updated.append(event);
		}
		return updated;
	}

	/** Whether the recording was made against a block catalog source; see the class documentation. */
	public boolean verified() {
		return this.verified;
	}

	/** The interaction permission the recording was made with. */
	public Interaction interaction() {
		return this.interaction;
	}

	/** Who produced each checkpoint, the first entry for the initial state; a copy. */
	public List<TraceProducer> checkpointProducers() {
		return List.copyOf(this.producers);
	}

	/** The recorded events in order; a copy. */
	public List<SimulationEvent> events() {
		return List.copyOf(this.events);
	}

	/**
	 * Appends an event with the checkpoint another producer observed after it. The current
	 * simulation is discarded, since it no longer describes the recording; {@link #replay} judges
	 * agreement.
	 */
	public void appendObserved(final SimulationEvent event, final SimulationCheckpoint.Checkpoint observedCheckpoint,
	    final TraceProducer producer) {
		this.current = null;
		this.events.add(Objects.requireNonNull(event, "event"));
		this.checkpoints.add(observedCheckpoint.encode());
		this.producers.add(Objects.requireNonNull(producer, "producer"));
	}

	/**
	 * Applies {@code event} to the current simulation and records its checkpoint. Refuses with
	 * {@link IllegalStateException} when no simulation is executing; see {@link #forkCurrent}.
	 */
	public void append(final SimulationEvent event) {
		if (this.current == null) {
			throw new IllegalStateException("recording has no executed simulation; call replay or resume first");
		}
		event.apply(this.current);
		this.events.add(event);
		this.checkpoints.add(SimulationCheckpoint.capture(this.current).encode());
		this.producers.add(TraceProducer.PURE_KERNEL);
	}

	/**
	 * Replays every recorded event from the initial state and compares the checkpoint after each
	 * against the recorded one. Refuses with {@link IllegalStateException} at the first
	 * disagreement, naming the event index and the first differing field. Returns the simulation
	 * after the last event.
	 */
	public ScheduledSimulation replay(final BlockStateCatalog.Source source) {
		ScheduledSimulation state = create(source);
		check(0, state);
		for (int i = 0; i < this.events.size(); i++) {
			this.events.get(i).apply(state);
			check(i + 1, state);
		}
		return state;
	}

	/** Compares at the recorded checkpoint's boundary schema, so older recordings stay raw and exact. */
	private void check(final int index, final ScheduledSimulation state) {
		byte[] recorded = this.checkpoints.get(index);
		byte[] fresh = SimulationCheckpoint.encodeLike(recorded, state);
		if (!Arrays.equals(recorded, fresh)) {
			throw new IllegalStateException("scheduled state mismatch at event " + index + ": "
			    + SimulationCheckpoint.firstDifference(recorded, fresh).orElse("bytes differ"));
		}
	}

	/** Writes the recording to {@code path} as one gzip stream, replacing any existing file. */
	public void write(final Path path) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
			out.writeUTF(MAGIC);
			out.writeInt(FORMAT_VERSION);
			out.writeUTF(this.version);
			out.writeBoolean(this.verified);
			blob(out, this.catalogFingerprint);
			out.writeUTF(this.interaction.name());
			SimulationStateCodec.write(this.initial, out);
			StringWriter text = new StringWriter();
			WorldSnapshotCodec.write(this.world, text);
			blob(out, text.toString().getBytes(StandardCharsets.UTF_8));
			out.writeInt(this.events.size());
			out.writeUTF(this.producers.getFirst().name());
			blob(out, this.checkpoints.getFirst());
			for (int i = 0; i < this.events.size(); i++) {
				SimulationEvent event = this.events.get(i);
				if (event instanceof SimulationEvent.ClientTick tick) {
					out.writeUTF(CLIENT_TICK_EVENT);
					out.writeByte(tick.input().packedInput());
					out.writeInt(Float.floatToRawIntBits(tick.input().yRot()));
					out.writeInt(Float.floatToRawIntBits(tick.input().xRot()));
				} else {
					out.writeUTF(((SimulationEvent.Phase) event).name());
				}
				out.writeUTF(this.producers.get(i + 1).name());
				blob(out, this.checkpoints.get(i + 1));
			}
		}
	}

	/**
	 * Reads the recording at {@code path}. Refuses with {@link IOException} another format
	 * version, a Minecraft version other than {@code expectedVersion}, a verified recording read
	 * without a {@code source} or with one producing a different catalog, or a malformed file. The
	 * recorded checkpoints are not replayed; call {@link #replay} to judge them.
	 */
	public static ScheduledRecording read(
	    final Path path, final String expectedVersion, final BlockStateCatalog.Source source) throws IOException {
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
			if (!in.readUTF().equals(MAGIC)) {
				throw new IOException("not a stride scheduled recording");
			}
			int format = in.readInt();
			if (format != FORMAT_VERSION) {
				throw new IOException("scheduled recording format version is " + format
				    + "; this library reads version " + FORMAT_VERSION + " only");
			}
			String version = in.readUTF();
			if (!version.equals(expectedVersion)) {
				throw new IOException("scheduled recording Minecraft version mismatch");
			}
			boolean verified = in.readBoolean();
			byte[] fingerprint = blob(in);
			Interaction interaction = Interaction.valueOf(in.readUTF());
			SimulationState initial = SimulationStateCodec.read(in);
			WorldSnapshot world = WorldSnapshotCodec.read(
			    new BufferedReader(new StringReader(new String(blob(in), StandardCharsets.UTF_8))));
			if (verified && source == null) {
				throw new IOException("a verified recording needs its block catalog source");
			}
			ScheduledRecording recording =
			    new ScheduledRecording(initial, world, version, interaction, verified ? source : null, false);
			if (!Arrays.equals(fingerprint, recording.catalogFingerprint)) {
				throw new IOException("block catalog fingerprint differs from the recording's");
			}
			int count = in.readInt();
			if (count < 0 || count > MAX_EVENTS) {
				throw new IOException("invalid event count");
			}
			recording.producers.add(TraceProducer.valueOf(in.readUTF()));
			recording.checkpoints.add(blob(in));
			for (int i = 0; i < count; i++) {
				String name = in.readUTF();
				SimulationEvent event = name.equals(CLIENT_TICK_EVENT)
				    ? new SimulationEvent.ClientTick(PlayerInput.ofPacked(
				          in.readByte(), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt())))
				    : SimulationEvent.Phase.valueOf(name);
				recording.events.add(event);
				recording.producers.add(TraceProducer.valueOf(in.readUTF()));
				recording.checkpoints.add(blob(in));
			}
			if (in.read() != -1) {
				throw new IOException("trailing scheduled recording data");
			}
			return recording;
		} catch (IllegalArgumentException | IllegalStateException | UnimplementedMechanicException invalid) {
			throw new IOException("invalid scheduled recording", invalid);
		}
	}

	private static byte[] fingerprint(final BlockStateCatalog catalog) {
		try {
			return MessageDigest.getInstance(FINGERPRINT_ALGORITHM).digest(BlockStateCatalogCodec.bytes(catalog));
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}

	private static void blob(final DataOutputStream out, final byte[] data) throws IOException {
		out.writeInt(data.length);
		out.write(data);
	}

	private static byte[] blob(final DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > MAX_BLOB_BYTES) {
			throw new IOException("invalid blob size");
		}
		byte[] data = in.readNBytes(size);
		if (data.length != size) {
			throw new EOFException();
		}
		return data;
	}
}
