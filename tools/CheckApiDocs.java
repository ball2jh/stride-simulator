import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;
import javax.tools.ToolProvider;

/** Checks declared public type descriptions using the JDK's Java parser. */
public final class CheckApiDocs {
	public static void main(String[] args) throws Exception {
		Path root = Path.of(args.length == 0 ? "src/main/java" : args[0]);
		List<Path> sources;
		try (var paths = Files.walk(root)) {
			sources = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("A full JDK is required");
		List<String> failures = new ArrayList<>();
		try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(
			    null, files, null, List.of("-proc:none"), null, files.getJavaFileObjectsFromPaths(sources));
			DocTrees docs = DocTrees.instance(task);
			for (CompilationUnitTree unit : task.parse()) {
				Path path = Path.of(unit.getSourceFile().toUri());
				Path description = path.resolveSibling("package-info.java");
				if (!Files.isRegularFile(description)) failures.add("Missing " + description);
				new TreePathScanner<Void, Void>() {
					@Override
					public Void visitClass(ClassTree type, Void ignored) {
						if (!type.getSimpleName().isEmpty() && publiclyAccessible(getCurrentPath())) {
							var comment = docs.getDocCommentTree(getCurrentPath());
							if (comment == null || comment.getFirstSentence().isEmpty()) {
								failures.add(path + ": " + type.getSimpleName() + " needs a Javadoc description");
							}
						}
						return super.visitClass(type, ignored);
					}
				}.scan(unit, null);
			}
		}
		if (!failures.isEmpty())
			throw new IllegalStateException(String.join("\n", failures.stream().distinct().toList()));
		System.out.println("Public type descriptions checked in " + sources.size() + " source files.");
	}

	private static boolean publiclyAccessible(TreePath path) {
		for (TreePath current = path; current != null; current = current.getParentPath()) {
			if (!(current.getLeaf() instanceof ClassTree type)) continue;
			TreePath parent = current.getParentPath();
			Tree container = parent == null ? null : parent.getLeaf();
			boolean interfaceMember = container instanceof ClassTree owner
			    && (owner.getKind() == Tree.Kind.INTERFACE || owner.getKind() == Tree.Kind.ANNOTATION_TYPE);
			if (!interfaceMember && !type.getModifiers().getFlags().contains(Modifier.PUBLIC)) return false;
		}
		return true;
	}
}
