import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

/**
 * Checks that the public API is documented, using the JDK's own Java parser.
 *
 * <p>Usage: {@code CheckApiDocs [--strict] [--report FILE] ROOT...}. Each root is a source tree. In every
 * root, each package needs a {@code package-info.java} and each publicly accessible type a Javadoc first
 * sentence. With {@code --strict}, public constructors, methods and fields of publicly accessible types in
 * exported packages need one too, except members annotated {@code @Override} and compact record
 * constructors. A package is exported when the root's {@code module-info.java} exports it, or always when
 * the root has no module descriptor. Failures go to stderr and the exit status is 1.
 */
public final class CheckApiDocs {
	private CheckApiDocs() {}

	/** Parses the roots named on the command line and reports every undocumented declaration. */
	public static void main(String[] args) throws IOException {
		boolean strict = false;
		Path report = null;
		List<Path> roots = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--strict" -> strict = true;
				case "--report" -> report = Path.of(args[++i]);
				default -> roots.add(Path.of(args[i]));
			}
		}
		if (roots.isEmpty()) {
			roots.add(Path.of("src/main/java"));
		}
		List<Path> sources = new ArrayList<>();
		for (Path root : roots) {
			try (var paths = Files.walk(root)) {
				sources.addAll(paths.filter(p -> p.toString().endsWith(".java")).sorted().toList());
			}
		}
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			System.err.println("A full JDK is required to check API documentation");
			System.exit(1);
		}
		Set<String> failures = new LinkedHashSet<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files =
		         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(
			    null, files, diagnostics, List.of("-proc:none"), null, files.getJavaFileObjectsFromPaths(sources));
			DocTrees docs = DocTrees.instance(task);
			SourcePositions positions = docs.getSourcePositions();
			List<CompilationUnitTree> units = new ArrayList<>();
			task.parse().forEach(units::add);
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
					failures.add(diagnostic.toString());
				}
			}
			Set<String> exported = new HashSet<>();
			Set<Path> modularRoots = new HashSet<>();
			for (CompilationUnitTree unit : units) {
				ModuleTree module = unit.getModule();
				if (module == null) {
					continue;
				}
				modularRoots.add(rootOf(roots, Path.of(unit.getSourceFile().toUri())));
				for (DirectiveTree directive : module.getDirectives()) {
					if (directive instanceof ExportsTree exports) {
						exported.add(exports.getPackageName().toString());
					}
				}
			}
			for (CompilationUnitTree unit : units) {
				Path path = Path.of(unit.getSourceFile().toUri());
				Path description = path.resolveSibling("package-info.java");
				boolean moduleDescriptor = path.getFileName().toString().equals("module-info.java");
				if (!moduleDescriptor && !Files.isRegularFile(description)) {
					failures.add("Missing " + description);
				}
				String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
				boolean checkMembers =
				    strict && (exported.contains(packageName) || !modularRoots.contains(rootOf(roots, path)));
				CharSequence text = unit.getSourceFile().getCharContent(true);
				new TreePathScanner<Void, Void>() {
					@Override
					public Void visitClass(ClassTree type, Void ignored) {
						if (!type.getSimpleName().isEmpty() && publiclyAccessible(getCurrentPath())
						    && !documented(docs, getCurrentPath())) {
							failures.add(path + ": type " + type.getSimpleName() + " needs a Javadoc description");
						}
						return super.visitClass(type, ignored);
					}

					@Override
					public Void visitMethod(MethodTree method, Void ignored) {
						if (checkMembers && publicMember(method.getModifiers().getFlags(), getCurrentPath())
						    && !overrides(method) && !compactConstructor(method, unit, positions, text)
						    && !documented(docs, getCurrentPath())) {
							failures.add(path + ": " + owner(getCurrentPath()) + "." + memberName(method)
							    + " needs a Javadoc description");
						}
						return null;
					}

					@Override
					public Void visitVariable(VariableTree field, Void ignored) {
						if (checkMembers && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
						    && publicMember(field.getModifiers().getFlags(), getCurrentPath())
						    && !documented(docs, getCurrentPath())) {
							failures.add(path + ": " + owner(getCurrentPath()) + "." + field.getName()
							    + " needs a Javadoc description");
						}
						return null;
					}
				}.scan(unit, null);
			}
		}
		if (!failures.isEmpty()) {
			failures.forEach(System.err::println);
			System.err.println(failures.size() + " API documentation failure(s)");
			System.exit(1);
		}
		String summary = (strict ? "Public type and member" : "Public type") + " descriptions checked in "
		    + sources.size() + " source files.";
		System.out.println(summary);
		if (report != null) {
			Files.createDirectories(report.getParent());
			Files.writeString(report, summary + System.lineSeparator());
		}
	}

	private static Path rootOf(List<Path> roots, Path path) {
		for (Path root : roots) {
			if (path.toAbsolutePath().startsWith(root.toAbsolutePath())) {
				return root;
			}
		}
		return path.getRoot();
	}

	private static boolean documented(DocTrees docs, TreePath path) {
		DocCommentTree comment = docs.getDocCommentTree(path);
		return comment != null && !comment.getFirstSentence().isEmpty();
	}

	private static boolean overrides(MethodTree method) {
		return method.getModifiers().getAnnotations().stream().anyMatch(
		    annotation -> annotation.getAnnotationType().toString().equals("Override"));
	}

	/** A compact record constructor has no parameter list in the source, so no {@code (} precedes its body. */
	private static boolean compactConstructor(
	    MethodTree method, CompilationUnitTree unit, SourcePositions positions, CharSequence text) {
		if (!method.getName().contentEquals("<init>") || method.getBody() == null) {
			return false;
		}
		int start = (int) positions.getStartPosition(unit, method);
		int body = (int) positions.getStartPosition(unit, method.getBody());
		return start >= 0 && body > start && text.subSequence(start, body).chars().noneMatch(c -> c == '(');
	}

	private static String memberName(MethodTree method) {
		return method.getName().contentEquals("<init>") ? "<init>" : method.getName().toString();
	}

	private static String owner(TreePath path) {
		for (TreePath current = path.getParentPath(); current != null; current = current.getParentPath()) {
			if (current.getLeaf() instanceof ClassTree type) {
				return type.getSimpleName().toString();
			}
		}
		return "?";
	}

	/** Whether a member with these modifiers is public, counting the implicit public of interface members. */
	private static boolean publicMember(Set<Modifier> flags, TreePath path) {
		if (!publiclyAccessible(path.getParentPath())) {
			return false;
		}
		Tree container = path.getParentPath().getLeaf();
		boolean interfaceMember = container instanceof ClassTree owner
		    && (owner.getKind() == Tree.Kind.INTERFACE || owner.getKind() == Tree.Kind.ANNOTATION_TYPE);
		if (interfaceMember) {
			return !flags.contains(Modifier.PRIVATE);
		}
		return flags.contains(Modifier.PUBLIC);
	}

	private static boolean publiclyAccessible(TreePath path) {
		for (TreePath current = path; current != null; current = current.getParentPath()) {
			if (!(current.getLeaf() instanceof ClassTree type)) {
				continue;
			}
			if (type.getSimpleName().isEmpty()) {
				return false;
			}
			TreePath parent = current.getParentPath();
			Tree container = parent == null ? null : parent.getLeaf();
			boolean interfaceMember = container instanceof ClassTree owner
			    && (owner.getKind() == Tree.Kind.INTERFACE || owner.getKind() == Tree.Kind.ANNOTATION_TYPE);
			if (!interfaceMember && !type.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
				return false;
			}
		}
		return true;
	}
}
