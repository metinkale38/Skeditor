package de.tubs.skeditor.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import de.tubs.skeditor.utils.FileUtils;
import edu.cmu.cs.ls.keymaerax.codegen.CControllerGenerator;
import edu.cmu.cs.ls.keymaerax.codegen.CGenerator;
import edu.cmu.cs.ls.keymaerax.core.BaseVariable;
import edu.cmu.cs.ls.keymaerax.core.Box;
import edu.cmu.cs.ls.keymaerax.core.Formula;
import edu.cmu.cs.ls.keymaerax.core.Imply;
import edu.cmu.cs.ls.keymaerax.core.Program;
import edu.cmu.cs.ls.keymaerax.core.StaticSemantics;
import edu.cmu.cs.ls.keymaerax.parser.ArchiveParser;
import edu.cmu.cs.ls.keymaerax.parser.ParsedArchiveEntry;
import scala.Tuple2;
import scala.collection.JavaConverters;
import scala.collection.JavaConverters$;
import scala.collection.immutable.Map;

public class CodeGenerator {

	private final Set<String> parameters = new HashSet<>();
	private final Set<String> inputs = new HashSet<>();
	private final Set<String> states = new HashSet<>();

	private String generatedCppCode;
	private String hybridProgram;
	private File buildPath;
	private String name;
	private HashMap<String, String> graphParameters;

	public CodeGenerator(String name) {
		this.name = name;
	}

	public CodeGenerator setGraphParameters(HashMap<String, String> graphParameters) {
		this.graphParameters = graphParameters;
		return this;
	}

	public CodeGenerator setHybridProgram(String code) {
		hybridProgram = code;
		return this;
	}

	public CodeGenerator setBuildPath(String path) {
		this.buildPath = new File(path);
		buildPath.mkdir();
		return this;
	}

	public String build() throws LaunchException {
		try {
			generateCppCode();
			parseCCode();
			generateCProject();
			makeCProject();
			return getExecutablePath();
		} catch (LaunchException e) {
			throw e;
		} catch (Exception e) {
			throw new LaunchException("Failed to generate Executable for kyx file due to " + e.getMessage());
		}
	}

	private String getExecutablePath() {
		return new File(buildPath, "build/devel/lib/" + name + "/" + name).getAbsolutePath();
	}

	private void makeCProject() throws IOException, InterruptedException {
		File buildDir = new File(buildPath, "build");
		if (!buildDir.mkdir()) {
			throw new RuntimeException("Could not mkdir " + buildDir.getAbsolutePath());
		}

		CMDRunner.cmd("cmake ..").dir(buildDir.getAbsolutePath()).logFile("CMAKE.log").runBlocking();
		CMDRunner.cmd("make").dir(buildDir.getAbsolutePath()).logFile("MAKE.log").runBlocking();
	}

	private void generateCProject() throws IOException, LaunchException {
		FileUtils.deleteDirectory(buildPath);

		buildPath.mkdir();
		new File(buildPath, "src").mkdir();

		FileUtils.copyFromResource("/project_template/CMakeLists.txt", buildPath.getAbsolutePath() + "/CMakeLists.txt");
		FileUtils.copyFromResource("/project_template/package.xml", buildPath.getAbsolutePath() + "/package.xml");
		FileUtils.copyFromResource("/project_template/src/Skill.h", buildPath.getAbsolutePath() + "/src/Skill.h");

		FileUtils.replaceInFile(buildPath.getAbsolutePath() + "/CMakeLists.txt", "SKILL_NAME", name);
		FileUtils.replaceInFile(buildPath.getAbsolutePath() + "/package.xml", "SKILL_NAME", name);

		try (PrintWriter writer = new PrintWriter(new FileOutputStream(buildPath + "/src/main.cpp"))) {
			writer.println("#include \"Skill.h\"");
			writer.println("#include <string>");
			writer.println("#include <iostream>");
			writer.println("#include <math.h>");
			writer.println("#include <stdbool.h>");
			writer.println();
			writer.println();
			// parameters
			writer.println("typedef struct parameters {");
			this.parameters.forEach(p -> {
				writer.println("  long double " + p + ";");
			});
			writer.println("} Parameters;");
			writer.println();

			// states
			writer.println("typedef struct state {");
			this.states.forEach(p -> {
				writer.println("  long double " + p + ";");
			});
			writer.println("} State;");
			writer.println();

			// input
			writer.println("typedef struct input {");
			this.inputs.forEach(p -> {
				writer.println("  long double " + p + ";");
			});
			writer.println("} Input;");
			writer.println();

			int ctrlStepStart = generatedCppCode.indexOf("state ctrlStep(");
			writer.println("/* START OF KEYMAERAX GENERATED CODE */");
			writer.println(generatedCppCode.substring(ctrlStepStart).replace("state state;", "State state;"));
			writer.println("/* END OF KEYMAERAX GENERATED CODE */");

			writer.println("int main(int argc, const char **argv) {");

			// init structs
			writer.println("    parameters params;");
			writer.println("    state states;");
			writer.println("    input inputs;");
			writer.println();

			if (parameters.contains("ep")) {
				writer.println("    params.ep = EP;");
				parameters.remove("ep");
				writer.println();
			}

			for (String param : parameters) {
				String value = graphParameters.getOrDefault(param, null);
				if (value != null) {
					writer.println("    params." + param + " = " + value + ";");
				} else {
					throw new LaunchException("could not find Parameter " + param + " in sked file");
				}
			}

			// init
			writer.println("    init(\"" + name.replace(" ", "_") + "\");");
			writer.println();

			// init input variables
			states.forEach(state -> writer
					.println("    registerInputVar(\"" + state + "\", &states." + state + ");"));
			inputs.forEach(input -> writer
					.println("    registerInputVar(\"" + input + "\", &inputs." + input + ");"));
			writer.println();

			// init output variables
			states.forEach(state -> writer
					.println("    registerOutputVar(\"" + state + "\", &states." + state + ");"));
			writer.println();
			writer.println();

			// while loop
			writer.println("    while(loop()) {");
			writer.println("        states = ctrlStep(states, &params, &inputs);   ");
			writer.println("    }");

			writer.println();
			writer.println("    return 0;");
			writer.println("}");

		}
	}

	private void parseCCode() {
		Set<String> varType = null;
		for (String line : generatedCppCode.split("\n")) {
			if (line.contains("typedef struct state"))
				varType = states;
			if (line.contains("typedef struct input")) {
				if (!line.contains(";")) {
					varType = inputs;
				}
			}
			if (line.contains("typedef struct parameter"))
				varType = parameters;
			if (line.contains("}"))
				varType = null;

			if (varType != null && line.contains("long double")) {
				String varName = line.split("long double")[1].split(";")[0].trim();
				varType.add(varName);
			}
		}

	}

	private void generateCppCode() throws LaunchException {
		init();

		scala.collection.immutable.List<ParsedArchiveEntry> archives = ArchiveParser.parser().parse(hybridProgram,
				true);
		for (int i = 0; i < archives.length(); i++) {
			ParsedArchiveEntry archive = archives.apply(i);
			Formula model = (Formula) archive.model();
			Program prg = ((Box) ((Imply) model).right()).program();

			@SuppressWarnings({ "unchecked", "rawtypes" })
			scala.collection.immutable.List<Object> symbols = StaticSemantics.boundVars(model).symbols().toList();
			ArrayList<BaseVariable> vars = new ArrayList<>();
			for (int j = 0; j < symbols.size(); j++) {
				Object s = symbols.apply(j);
				if (s instanceof BaseVariable) {
					vars.add((BaseVariable) s);
				}
			}

			Tuple2<String, String> result = new CGenerator(new CControllerGenerator()).apply(prg,
					JavaConverters.asScalaSet(vars.stream().collect(Collectors.toSet())).toSet(),
					CGenerator.getInputs(prg));
			generatedCppCode = result._1;
		}
	}

	private void init() {
		edu.cmu.cs.ls.keymaerax.btactics.ToolProvider$.MODULE$
				.setProvider(new edu.cmu.cs.ls.keymaerax.btactics.Z3ToolProvider(toScalaMap(getZ3Config())));

		edu.cmu.cs.ls.keymaerax.Configuration$.MODULE$
				.setConfiguration(edu.cmu.cs.ls.keymaerax.FileConfiguration$.MODULE$);

		java.util.HashMap<String, String> javaConfig = new java.util.HashMap<>();
		javaConfig.put(edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool.INIT_DERIVATION_INFO_REGISTRY(), "false");
		javaConfig.put(edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool.INTERPRETER(),
				edu.cmu.cs.ls.keymaerax.bellerophon.ExhaustiveSequentialInterpreter$.class.getSimpleName());
		Map<String, String> config = toScalaMap(javaConfig);

		edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool$.MODULE$.init(config);

		System.out.println("KeYmaeraX initiliazied = " + edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool.isInitialized());
		if (edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool.isInitialized()) {
			System.out.println(edu.cmu.cs.ls.keymaerax.tools.KeYmaeraXTool.name());
		}
	}

	public static <A, B> Map<A, B> toScalaMap(java.util.HashMap<A, B> m) {
		return JavaConverters$.MODULE$.mapAsScalaMapConverter(m).asScala().toMap(scala.Predef$.MODULE$.$conforms());
	}

	public static java.util.HashMap<String, String> getZ3Config() {
		java.util.HashMap<String, String> c = new java.util.HashMap<>();
		c.put("z3Path", "/home/metin/.keymaerax/z3"); // path
		return c;
	}

}
