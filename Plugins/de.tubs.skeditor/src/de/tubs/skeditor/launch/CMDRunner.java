package de.tubs.skeditor.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

public class CMDRunner {
	private static ExecutorService EXECUTOR = Executors.newCachedThreadPool();
	private static List<Runnable> SCHEDULED_TASKS = new ArrayList();
	
	private String path;
	private String cmd;
	private String logFile;
	private MessageConsoleStream out;
	private MessageConsole console;

	private CMDRunner() {
	}

	public static CMDRunner cmd(String cmd) {
		CMDRunner instance = new CMDRunner();
		instance.cmd = cmd;
		return instance;
	}

	public CMDRunner dir(String path) {
		this.path = path;
		return this;
	}

	public CMDRunner logFile(String log) {
		if (out != null)
			throw new RuntimeException("cannot use console() and logFile() for the same CMDRunner");
		this.logFile = log;
		return this;
	}

	public int runBlocking() throws IOException {
		ProcessBuilder pb = new ProcessBuilder("bash", "-ic", cmd);
		if (path != null) {
			pb = pb.directory(new File(path));
		}

		Process p = pb.start();

		if (out != null) {
			InputStream is = p.getInputStream();
			InputStream er = p.getErrorStream();
			new StreamGobbler(is, out).start();
			new StreamGobbler(er, out).start();
		}

		if (logFile != null) {
			try (FileOutputStream fos = new FileOutputStream(new File(path + "/" + logFile))) {
				InputStream is = p.getInputStream();
				InputStream er = p.getErrorStream();
				new StreamGobbler(is, fos).start();
				new StreamGobbler(er, fos).start();
			}
		}

		try {
			return p.waitFor();
		} catch (InterruptedException e) {
			p.destroy();
			return -1;
		}
	}

	public void scheduleTask(String name) {
		SCHEDULED_TASKS.add(new Runnable() {
			@Override
			public void run() {
				CMDRunner.this.run(name);
			}
		});
	}

	public void run(String name) {
		if (EXECUTOR.isShutdown()) {
			EXECUTOR = Executors.newCachedThreadPool();
		}
		EXECUTOR.execute(new Runnable() {
			public void run() {
				console(name);
				try {
					out.println(name + " started");
					runBlocking();
					out.println(name + " ended");
				} catch (IOException e) {
					out.println(name + " failed");
					e.printStackTrace(new PrintStream(out));
				}
			}
		});

	}

	public synchronized static void runAllSheduled() {
		for(Runnable runnable: SCHEDULED_TASKS) {
			runnable.run();
		}
		SCHEDULED_TASKS.clear();
	}
	
	public static boolean shutdownAndWait(long time) throws InterruptedException {
		if (!EXECUTOR.isShutdown())
			EXECUTOR.shutdown();
		return EXECUTOR.awaitTermination(time, TimeUnit.MILLISECONDS);
	}

	public static boolean interruptAndWait() throws InterruptedException {
		EXECUTOR.shutdownNow();
		return EXECUTOR.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
	}
	

	public CMDRunner console(String name) {
		if (logFile != null)
			throw new RuntimeException("cannot use console() and logFile() for the same CMDRunner");
		ConsolePlugin plugin = ConsolePlugin.getDefault();
		IConsoleManager conMan = plugin.getConsoleManager();
		IConsole[] existing = conMan.getConsoles();
		console = null;
		for (int i = 0; i < existing.length; i++) {
			if (name.equals(existing[i].getName())) {
				console = ((MessageConsole) existing[i]);
			}
		}
		if (console == null) {
			console = new MessageConsole(name, null);
			// console.activate();
			console.clearConsole();
			conMan.addConsoles(new IConsole[] { console });
		}
		out = console.newMessageStream();
		return this;
	}

	private static class StreamGobbler extends Thread {
		InputStream is;
		OutputStream os;

		private StreamGobbler(InputStream is, OutputStream os) {
			this.is = is;
			this.os = os;
		}

		@Override
		public void run() {
			try {
				byte[] buf = new byte[8192];
				int length;
				while ((length = is.read(buf)) > 0) {
					os.write(buf, 0, length);
				}
			} catch (IOException e) {
			}
		}
	}

}
