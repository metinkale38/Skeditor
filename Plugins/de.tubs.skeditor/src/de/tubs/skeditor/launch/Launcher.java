package de.tubs.skeditor.launch;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;

import SkillGraph.Graph;
import SkillGraph.Node;
import SkillGraph.Parameter;

public class Launcher extends LaunchConfigurationDelegate {

	private String skedPath;
	private PrintStream out;
	private String buildPath;
	private String worldPath;

	private Graph graph;
	private HashMap<String, String> parameters = new HashMap<>();
	private IProgressMonitor monitor;

	@Override
	public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
			throws CoreException {
		MessageConsole console = findConsole("Skill");
		console.activate();
		console.clearConsole();
		out = new PrintStream(console.newOutputStream());
		this.monitor = monitor;

		out.println("===Attributes===");
		configuration.getAttributes().forEach((key, value) -> out.println(key + " = " + value));
		out.println("================");
		out.println();

		skedPath = configuration.getAttribute(LaunchConfigurationAttributes.SKED_PATH, (String) null);
		buildPath = configuration.getAttribute(LaunchConfigurationAttributes.BUILD_PATH, (String) null);
		worldPath = configuration.getAttribute(LaunchConfigurationAttributes.WORLD_PATH, (String) null);

		new File(buildPath).mkdir();
		
	
		try {
			try {
				parseSked(skedPath);
				buildAndScheduleHybridProgram(graph.getNodes());
				buildAndSchedulePrograms(graph.getNodes());
				
				// all tasks are scheduled, but not actually run, start roscore and gazebo
				CMDRunner.cmd("/opt/ros/noetic/bin/roscore").dir(buildPath).run("roscore");
				Thread.sleep(1000); // make sure roscore initialized
				CMDRunner.cmd("rosrun gazebo_ros gazebo " + worldPath).dir(buildPath).run("gazebo");
				Thread.sleep(5000); // make sure gazebo is inizialized
				
				out.println("Starting all Skills");
				CMDRunner.runAllSheduled();			
				
			} catch (LaunchException | InterruptedException e) {
				out.println();
				out.println("Build failed: ");
				out.println(e.getMessage());
			}
		} finally {

			try {
				while (!CMDRunner.shutdownAndWait(1000)) {
					if (monitor.isCanceled()) {
						out.println("Stopping all processes");
						CMDRunner.interruptAndWait();
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			out.println("All tasks are done, exiting launcher...");

		}
	}

	private void buildAndSchedulePrograms(EList<Node> nodes) throws LaunchException {
		out.println();
		out.println("Building programs...");
		for (int i = 0; i < nodes.size(); i++) {
			if(monitor.isCanceled()) return;
			Node node = nodes.get(i);
			out.print(node.getName().replace(" ", "_"));
			out.print(": ");

			String programPath = node.getProgramPath();
			if (programPath == null || programPath.equals("")) {
				out.println("Skip");
				continue;
			}

			File program = new File(programPath);
			if (!program.exists()) {
				throw new LaunchException("Program (" + programPath + ") does not exist");
			}

			if (program.isFile()) {
				if (program.canExecute()) {
					out.println("Executable (" + program.getName() + ")");
				} else {
					throw new LaunchException("File (" + program.getName() + ") is not executable");
				}
			} else {
				out.print("CMAKE ");
				initCMake(node);
				out.print("- MAKE");
				makeProject(node);
				out.println("- OK");
				execute(node);
			}
		}
	}

	private void buildAndScheduleHybridProgram(List<Node> nodes) throws LaunchException {
		for (Node node : nodes) {
			if(monitor.isCanceled()) return;
			if (node.getController() != null) {
				for (int i = 0; i < node.getController().size(); i++) {
					String ctrl = node.getController().get(i).getCtrl();
					String name = node.getName().replace(" ", "_");

					out.println("generate executable for kyx script");
					String path = new CodeGenerator(name).setBuildPath(buildPath + "/kyx")
							.setGraphParameters(parameters).setHybridProgram(ctrl).build();
					System.out.println(buildPath);
					System.out.println(path);
					CMDRunner.cmd(path).dir(buildPath).scheduleTask(name);

				}
			}
		}
	}

	private void execute(Node node) throws LaunchException {
		String name = node.getName().replace(" ", "_");
		File buildPath = new File(this.buildPath + "/" + name);
		File execPath = new File(buildPath, "devel/lib/" + name + "/" + name);

		if (!execPath.exists())
			throw new LaunchException("could not find executable " + execPath.getAbsolutePath());

		CMDRunner.cmd(execPath.getAbsolutePath()).dir(buildPath.getAbsolutePath()).scheduleTask(name);
	}

	private void makeProject(Node node) throws LaunchException {
		String name = node.getName().replace(" ", "_");
		File buildPath = new File(this.buildPath + "/" + name);

		try {

			if (CMDRunner.cmd("make").dir(buildPath.getAbsolutePath()).logFile("SKEDITOR_MAKE.log")
					.runBlocking() != 0) {
				throw new LaunchException(
						"Could not make project, see " + new File(buildPath, "SKEDITOR_MAKE.log").getAbsolutePath());
			}
		} catch (IOException e) {
			throw new LaunchException("could not make project");
		}
	}

	private void initCMake(Node node) throws LaunchException {
		String name = node.getName().replace(" ", "_");
		File buildPath = new File(this.buildPath + "/" + name);
		buildPath.mkdir();

		try {
			if (CMDRunner.cmd("cmake " + node.getProgramPath()).dir(buildPath.getAbsolutePath())
					.logFile("SKEDITOR_CMAKE.log").runBlocking() != 0) {
				throw new LaunchException("Could not initialize cmake build directory, see "
						+ new File(buildPath, "SKEDITOR_CMAKE.log").getAbsolutePath());
			}
		} catch (IOException | LaunchException e) {
			throw new LaunchException("could not cmake project");
		}

	}

	private void parseSked(String skedFile) throws LaunchException {
		try {
			ResourceSet rs = new ResourceSetImpl();
			URI uri = URI.createFileURI(skedFile);
			TransactionalEditingDomain editingDomain = TransactionUtil.getEditingDomain(rs);
			if (editingDomain == null) {
				editingDomain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(rs);
			}

			Resource r = rs.getResource(uri, true);
			r.load(null);

			for (Object o : r.getContents()) {
				if (o instanceof Graph) {
					graph = (Graph) o;
					for (Parameter param : graph.getParameterList()) {
						out.println("Found " + param.getAbbreviation() + "=" + param.getDefaultValue());
						parameters.put(param.getAbbreviation(), param.getDefaultValue());
					}
				}
			}

		} catch (IOException e) {
			throw new LaunchException("Could not load sked file");
		}
	}

	private MessageConsole findConsole(String name) {
		ConsolePlugin plugin = ConsolePlugin.getDefault();
		IConsoleManager conMan = plugin.getConsoleManager();
		IConsole[] existing = conMan.getConsoles();
		for (int i = 0; i < existing.length; i++)
			if (name.equals(existing[i].getName()))
				return (MessageConsole) existing[i];
		// no console found, so create a new one
		MessageConsole myConsole = new MessageConsole(name, null);
		conMan.addConsoles(new IConsole[] { myConsole });
		return myConsole;
	}

}