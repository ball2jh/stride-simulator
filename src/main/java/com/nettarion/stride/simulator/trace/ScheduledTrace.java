package com.nettarion.stride.simulator.trace;

import com.nettarion.stride.simulator.*;
import com.nettarion.stride.simulator.PlayerInput;
import com.nettarion.stride.simulator.ScheduledSimulation;
import com.nettarion.stride.simulator.SimulationEvent;
import com.nettarion.stride.simulator.SimulationState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.tick.ClientTick;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.SnapshotView;
import com.nettarion.stride.simulator.world.WorldSnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** A durable explicit event trace with an initial world/boundary and exact checkpoints after every event. */
public final class ScheduledTrace {
	private final SimulationState initial;
	private final WorldSnapshot world;
	private final String version;
	private final Boolean mayInteract;
	private final boolean verified;
	private final byte[] sourceFingerprint;
	private final ArrayList<SimulationEvent> events = new ArrayList<>();
	private final ArrayList<byte[]> checkpoints = new ArrayList<>();
	private final ArrayList<TraceProducer> producers = new ArrayList<>();
	private ScheduledSimulation recording;

	/** Start at an explicitly declared transport-empty boundary; the trace retains later in-flight payloads. */
	public ScheduledTrace(final SimulationState initial, final WorldSnapshot world, final String version,
	    final Boolean mayInteract, final BlockStateCatalog.Source source) {
		this(initial, world, version, mayInteract, source, true);
	}
	private ScheduledTrace(final SimulationState initial, final WorldSnapshot world, final String version,
	    final Boolean mayInteract, final BlockStateCatalog.Source source, final boolean computed) {
		this.initial = Objects.requireNonNull(initial);
		this.world = Objects.requireNonNull(world);
		this.version = Objects.requireNonNull(version);
		this.mayInteract = mayInteract;
		this.verified = source != null;
		if (source != null) {
			if (!source.minecraftVersion().equals(version))
				throw new IllegalArgumentException("source version mismatch");
			this.sourceFingerprint = fingerprint(source.catalog(world));
		} else
			this.sourceFingerprint = new byte[0];
		if (computed) {
			this.recording = create(source);
			this.checkpoints.add(SimulationCheckpoint.capture(this.recording));
			this.producers.add(TraceProducer.PURE_KERNEL);
		}
	}
	/** Start independent recording: no simulator phase or world compilation runs while recording. */
	public static ScheduledTrace observed(final SimulationState initial, final WorldSnapshot world,
	    final String version, final Boolean mayInteract, final BlockStateCatalog.Source source,
	    final byte[] initialCheckpoint, final TraceProducer producer) {
		var trace = new ScheduledTrace(initial, world, version, mayInteract, source, false);
		trace.checkpoints.add(initialCheckpoint.clone());
		trace.producers.add(Objects.requireNonNull(producer));
		return trace;
	}
	private ScheduledSimulation create(final BlockStateCatalog.Source source) {
		if (this.verified && (source == null || !source.minecraftVersion().equals(this.version)))
			throw new IllegalArgumentException("scheduled trace needs matching independent source verification");
		BlockStateCatalog catalog = source == null ? null : source.catalog(this.world);
		if (catalog != null && !Arrays.equals(this.sourceFingerprint, fingerprint(catalog)))
			throw new IllegalArgumentException("scheduled source provenance changed");
		SnapshotView view = catalog == null ? SnapshotView.compile(this.world)
		                                    : SnapshotView.compile(this.world, catalog, this.version);
		return new ScheduledSimulation(this.initial, view, this.mayInteract);
	}
	/** Explicitly executes the recorded prefix before allowing new simulator-produced checkpoints. */
	public ScheduledTrace resume(final BlockStateCatalog.Source source) {
		this.recording = replay(source);
		return this;
	}
	public ScheduledSimulation state() {
		if (this.recording == null)
			throw new IllegalStateException(
			    "recording has no executed simulator state; call replay or resume explicitly");
		return this.recording.fork();
	}
	/** Recompute a kernel-only consistency artifact at the current implementation; native evidence refuses. */
	public ScheduledTrace recomputeKernel(final BlockStateCatalog.Source source) {
		if (this.producers.stream().anyMatch(p -> p != TraceProducer.PURE_KERNEL))
			throw new IllegalArgumentException("native observations cannot be recomputed");
		var updated = new ScheduledTrace(this.initial, this.world, this.version, this.mayInteract, source);
		for (var event : this.events)
			updated.append(event);
		return updated;
	}

	/** Usage: INPUT VERSION CATALOG OUTPUT; writes a new kernel-only consistency artifact, preserving the input. */
	public static void main(String[] args) throws Exception {
		if (args.length != 4) throw new IllegalArgumentException("INPUT VERSION CATALOG OUTPUT");
		Path output = Path.of(args[3]);
		if (Files.exists(output)) throw new java.nio.file.FileAlreadyExistsException(output.toString());
		var catalog = BlockStateCatalogCodec.read(Path.of(args[2]), args[1]);
		var source = new BlockStateCatalog.Source(args[1], ignored -> catalog);
		var updated = read(Path.of(args[0]), args[1], source).recomputeKernel(source);
		updated.replay(source);
		updated.write(output);
	}

	public List<TraceProducer> checkpointProducers() {
		return List.copyOf(this.producers);
	}
	public List<SimulationEvent> events() {
		return List.copyOf(this.events);
	}
	/** Record independently observed raw evidence; replay, rather than the recorder, judges agreement. */
	public void appendObserved(
	    final SimulationEvent event, final byte[] observedCheckpoint, final TraceProducer producer) {
		this.recording = null;
		this.events.add(Objects.requireNonNull(event));
		this.checkpoints.add(Objects.requireNonNull(observedCheckpoint).clone());
		this.producers.add(Objects.requireNonNull(producer));
	}

	public void append(final SimulationEvent event) {
		if (this.recording == null)
			throw new IllegalStateException("cannot infer observations for an independent recording");
		event.apply(this.recording);
		this.events.add(event);
		this.checkpoints.add(SimulationCheckpoint.capture(this.recording));
		this.producers.add(TraceProducer.PURE_KERNEL);
	}
	/** Replay every recorded event and compare complete raw evidence at its exact boundary. */
	public ScheduledSimulation replay(final BlockStateCatalog.Source source) {
		ScheduledSimulation state = create(source);
		check(0, state);
		for (int i = 0; i < this.events.size(); i++) {
			this.events.get(i).apply(state);
			check(i + 1, state);
		}
		return state;
	}
	/** Compares at the archived checkpoint's boundary schema, so older archives stay raw and exact. */
	private void check(final int index, final ScheduledSimulation state) {
		byte[] archived = this.checkpoints.get(index);
		if (!Arrays.equals(archived, SimulationCheckpoint.captureLike(archived, state)))
			throw new IllegalStateException("scheduled state mismatch at event " + index);
	}
	/** Save one compressed artifact; numbers preserve raw float/double bits. */
	public void write(final Path path) throws IOException {
		try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
			out.writeUTF("stride-scheduled-trace");
			out.writeInt(2);
			out.writeUTF(this.version);
			out.writeBoolean(this.verified);
			blob(out, this.sourceFingerprint);
			out.writeByte(this.mayInteract == null ? -1 : this.mayInteract ? 1 : 0);
			BoundaryCodec.write(this.initial, out);
			var text = new StringWriter();
			WorldSnapshotCodec.write(this.world, text);
			blob(out, text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.writeInt(this.events.size());
			out.writeUTF(this.producers.getFirst().name());
			blob(out, this.checkpoints.getFirst());
			for (int i = 0; i < this.events.size(); i++) {
				SimulationEvent event = this.events.get(i);
				if (event instanceof SimulationEvent.ClientTick tick) {
					out.writeUTF("CLIENT_TICK");
					out.writeByte(tick.input().packedInput());
					out.writeInt(Float.floatToRawIntBits(tick.input().yRot()));
					out.writeInt(Float.floatToRawIntBits(tick.input().xRot()));
				} else
					out.writeUTF(((SimulationEvent.Phase) event).name());
				out.writeUTF(this.producers.get(i + 1).name());
				blob(out, this.checkpoints.get(i + 1));
			}
		}
	}
	/** Import and validate a trace; a verified trace cannot silently fall back to synthetic facts. */
	public static ScheduledTrace read(
	    final Path path, final String expectedVersion, final BlockStateCatalog.Source source) throws IOException {
		try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
			if (!in.readUTF().equals("stride-scheduled-trace") || in.readInt() != 2)
				throw new IOException("unsupported scheduled trace");
			String version = in.readUTF();
			if (!version.equals(expectedVersion)) throw new IOException("scheduled Minecraft version mismatch");
			boolean verified = in.readBoolean();
			byte[] fingerprint = blob(in);
			int permission = in.readByte();
			if (permission < -1 || permission > 1) throw new IOException("invalid interaction permission");
			SimulationState initial = BoundaryCodec.read(in);
			WorldSnapshot world = WorldSnapshotCodec.read(
			    new BufferedReader(new StringReader(new String(blob(in), java.nio.charset.StandardCharsets.UTF_8))));
			if (verified && source == null) throw new IOException("missing independent block source");
			ScheduledTrace trace =
			    new ScheduledTrace(initial, world, version, permission < 0 ? null : permission == 1, source, false);
			if (!Arrays.equals(fingerprint, trace.sourceFingerprint))
				throw new IOException("scheduled source fingerprint mismatch");
			int count = in.readInt();
			if (count < 0 || count > 1000000) throw new IOException("invalid event count");
			trace.producers.clear();
			trace.producers.add(TraceProducer.valueOf(in.readUTF()));
			trace.checkpoints.clear();
			trace.checkpoints.add(blob(in));
			for (int i = 0; i < count; i++) {
				String name = in.readUTF();
				SimulationEvent event = name.equals("CLIENT_TICK")
				    ? new SimulationEvent.ClientTick(PlayerInput.ofPacked(
				          in.readByte(), Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt())))
				    : SimulationEvent.Phase.valueOf(name);
				trace.events.add(event);
				trace.producers.add(TraceProducer.valueOf(in.readUTF()));
				trace.checkpoints.add(blob(in));
			}
			if (in.read() != -1) throw new IOException("trailing scheduled data");
			return trace;
		} catch (IllegalArgumentException | IllegalStateException | UnimplementedMechanicException invalid) {
			throw new IOException("invalid scheduled trace", invalid);
		}
	}
	private static byte[] fingerprint(final BlockStateCatalog catalog) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(BlockStateCatalogCodec.bytes(catalog));
		} catch (java.security.NoSuchAlgorithmException impossible) {
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
		if (size < 0 || size > 256 * 1024 * 1024) throw new IOException("invalid checkpoint size");
		byte[] data = in.readNBytes(size);
		if (data.length != size) throw new EOFException();
		return data;
	}
}
