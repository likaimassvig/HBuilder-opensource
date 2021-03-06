package com.aptana.ruby.internal.debug.core.model;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IThread;

import com.aptana.ruby.debug.core.RubyDebugCorePlugin;
import com.aptana.ruby.debug.core.RubyDebugModel;
import com.aptana.ruby.internal.debug.core.RubyDebuggerProxy;
import com.aptana.ruby.internal.debug.core.SuspensionPoint;

public class RubyDebugTarget extends RubyDebugElement implements IRubyDebugTarget
{
	public static int DEFAULT_PORT = 1098;
	private IProcess process;
	private boolean isTerminated;
	private ILaunch launch;
	private RubyThread[] threads;
	private RubyDebuggerProxy rubyDebuggerProxy;
	private int port;
	private File debugParameterFile;
	private ArrayList<IBreakpoint> fBreakPoints;
	private String host;

	private RubyDebugTarget(ILaunch launch, IProcess process, String host, int port)
	{
		super(null);
		this.launch = launch;
		this.host = host;
		this.port = port;
		this.process = process;
		this.threads = new RubyThread[0];
		this.fBreakPoints = new ArrayList<IBreakpoint>(5);
		this.isTerminated = false;
		if (DebugPlugin.getDefault() != null)
		{ // null only expected in Unit test
			initializeBreakpoints();
		}
		addDebugParameter("$RemoteDebugPort=" + port); //$NON-NLS-1$
	}

	public RubyDebugTarget(ILaunch launch, String host, int port)
	{
		this(launch, null, host, port);
	}

	public String getHost()
	{
		return host;
	}

	public void updateThreads()
	{
		RubyDebugCorePlugin.debug("udpating threads"); //$NON-NLS-1$
		ThreadInfo[] threadInfos = this.getRubyDebuggerProxy().readThreads();
		updateThreads(threadInfos);
	}

	public synchronized void updateThreads(ThreadInfo[] threadInfos)
	{
		if (isSuspended())
		{
			return;
		}
		DebugEvent[] events = updateThreadsInternal(threadInfos);
		DebugPlugin.getDefault().fireDebugEventSet(events);
	}

	// only public for testing
	public DebugEvent[] updateThreadsInternal(ThreadInfo[] threadInfos)
	{

		// preconditions:
		// 1) once a thread has died its id is never reused for new threads
		// again. Instead each new
		// thread gets an id which is the currently highest id + 1.
		List<DebugEvent> events = new ArrayList<DebugEvent>();
		RubyThread[] newThreads = new RubyThread[threadInfos.length];
		Set<Integer> newIds = new TreeSet<Integer>();
		boolean changed = false;
		for (int i = 0; i < threadInfos.length; i++)
		{
			ThreadInfo currentThreadInfo = threadInfos[i];
			RubyThread existingThread = getThreadById(currentThreadInfo.getId());

			if (existingThread == null)
			{
				newThreads[i] = new RubyThread(this, currentThreadInfo.getId(), currentThreadInfo.getStatus());
				DebugEvent ev = new DebugEvent(newThreads[i], DebugEvent.CREATE);
				events.add(ev);
			}
			else
			{
				newThreads[i] = existingThread;
				if (!existingThread.getStatus().equals(currentThreadInfo.getStatus()))
				{
					existingThread.setStatus(currentThreadInfo.getStatus());
					existingThread.updateName();
					DebugEvent ev = new DebugEvent(newThreads[i], DebugEvent.CHANGE);
					events.add(ev);
				}
			}
			newIds.add(newThreads[i].getId());
		}
		for (int i = 0; i < threads.length; i++)
		{
			if (!newIds.contains(threads[i].getId()))
			{
				DebugEvent ev = new DebugEvent(threads[i], DebugEvent.TERMINATE);
				events.add(ev);
			}
		}
		threads = newThreads;
		if (changed)
		{
			DebugEvent ev1 = new DebugEvent(this, DebugEvent.CHANGE, DebugEvent.CONTENT);
			events.add(ev1);
		}
		return events.toArray(new DebugEvent[] {});
	}

	public RubyThread getThreadById(int id)
	{
		for (int i = 0; i < threads.length; i++)
		{
			if (threads[i].getId() == id)
			{
				return threads[i];
			}
		}
		return null;
	}

	public void suspensionOccurred(SuspensionPoint suspensionPoint)
	{
		this.updateThreads();
		RubyThread thread = this.getThreadById(suspensionPoint.getThreadId());
		if (thread == null)
		{
			RubyDebugCorePlugin.log(IStatus.ERROR, MessageFormat.format("Thread with id {0} was not found", //$NON-NLS-1$
					suspensionPoint.getThreadId()));
			return;
		}
		thread.doSuspend(suspensionPoint);
	}

	public IThread[] getThreads()
	{
		return threads;
	}

	public boolean hasThreads() throws DebugException
	{
		return threads.length > 0;
	}

	public String getName() throws DebugException
	{
		return "Ruby"; //$NON-NLS-1$
	}

	public boolean supportsBreakpoint(IBreakpoint arg0)
	{
		return false;
	}

	public IDebugTarget getDebugTarget()
	{
		return this;
	}

	public ILaunch getLaunch()
	{
		return launch;
	}

	public boolean canTerminate()
	{
		return !isTerminated;
	}

	public boolean isTerminated()
	{
		return isTerminated;
	}

	public synchronized void terminate() throws DebugException
	{
		if (isTerminated)
		{
			return;
		}
		IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
		manager.removeBreakpointListener(this);
		try
		{
			rubyDebuggerProxy.stop();
			if (getProcess() != null)
			{
				getProcess().terminate();
			}
			this.threads = new RubyThread[0];
			isTerminated = true;
		}
		catch (DebugException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new DebugException(new Status(IStatus.ERROR, RubyDebugCorePlugin.PLUGIN_ID,
					DebugException.INTERNAL_ERROR, "Failed to stop ruby debugger proxy", e)); //$NON-NLS-1$
		}

		// launch is one of the listeners
		DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] { new DebugEvent(this, DebugEvent.TERMINATE) });

		// delete the debugParameteFile if it could be created
		if (debugParameterFile.exists())
		{
			boolean deleted = debugParameterFile.delete();
			if (!deleted)
			{
				RubyDebugCorePlugin.debug("Could not delete debugParameterFile:" + debugParameterFile.toURI()); //$NON-NLS-1$
			}
		}
	}

	public boolean canResume()
	{
		return false;
	}

	public boolean canSuspend()
	{
		return false;
	}

	public boolean isSuspended()
	{
		boolean isSuspended = false;
		for (int i = 0; i < getThreads().length; i++)
		{
			if (getThreads()[i].isSuspended())
			{
				isSuspended = true;
				break;
			}
		}
		return isSuspended;
	}

	public void resume() throws DebugException
	{
	}

	public void suspend() throws DebugException
	{
	}

	public void breakpointAdded(IBreakpoint breakpoint)
	{
		if (isTerminated)
		{
			return;
		}
		if (!getBreakpoints().contains(breakpoint))
		{
			if (this.getRubyDebuggerProxy() != null)
				this.getRubyDebuggerProxy().addBreakpoint(breakpoint);
			getBreakpoints().add(breakpoint);
		}
	}

	private List<IBreakpoint> getBreakpoints()
	{
		return fBreakPoints;
	}

	public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta arg1)
	{
		if (isTerminated)
		{
			return;
		}
		this.getRubyDebuggerProxy().removeBreakpoint(breakpoint);
		fBreakPoints.remove(breakpoint);
	}

	public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta arg1)
	{
		// is called e.g. after a line has been inserted before a breakpoint
		// or the enablement status has changed
		// in the first case it is essential that the debugger has reloaded the
		// file
		// so that the breakpoint moving is in synch with the new file
		if (isTerminated)
		{
			return;
		}
		this.getRubyDebuggerProxy().updateBreakpoint(breakpoint, arg1);
	}

	public boolean canDisconnect()
	{
		return false;
	}

	public void disconnect() throws DebugException
	{
	}

	public boolean isDisconnected()
	{
		return false;
	}

	public boolean supportsStorageRetrieval()
	{
		return false;
	}

	public IMemoryBlock getMemoryBlock(long arg0, long arg1) throws DebugException
	{
		return null;
	}

	public IProcess getProcess()
	{
		return process;
	}

	public void setProcess(IProcess process)
	{
		this.process = process;
	}

	public RubyDebuggerProxy getRubyDebuggerProxy()
	{
		return rubyDebuggerProxy;
	}

	public void setRubyDebuggerProxy(RubyDebuggerProxy rubyDebuggerProxy)
	{
		this.rubyDebuggerProxy = rubyDebuggerProxy;
	}

	public File getDebugParameterFile()
	{
		if (debugParameterFile == null)
		{
			try
			{
				debugParameterFile = File.createTempFile("classic-debug", ".rb"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			catch (IOException e)
			{
				RubyDebugCorePlugin.log("Could not create debugParameterFile", e); //$NON-NLS-1$
			}
		}
		return debugParameterFile;
	}

	/**
	 * Installs all Ruby breakpoints that currently exist in the breakpoint manager
	 */
	protected void initializeBreakpoints()
	{
		IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
		manager.addBreakpointListener(this);
		IBreakpoint[] bps = manager.getBreakpoints(RubyDebugModel.getModelIdentifier());
		for (int i = 0; i < bps.length; i++)
		{
			if (bps[i].getModelIdentifier().equals(RubyDebugModel.getModelIdentifier()))
			{
				breakpointAdded(bps[i]);
			}
		}
	}

	private boolean addDebugParameter(String line)
	{
		PrintWriter writer = null;
		try
		{
			writer = new PrintWriter(new FileWriter(getDebugParameterFile()));
			writer.println(line);
			writer.flush();
			return true;
		}
		catch (IOException ex)
		{
			RubyDebugCorePlugin.log(ex);
			return false;
		}
		finally
		{
			writer.close();
		}
	}

	public int getPort()
	{
		return port;
	}

	public boolean isUsingDefaultPort()
	{
		return getPort() == DEFAULT_PORT;
	}

	public IStatus load(String filename)
	{
		return getRubyDebuggerProxy().readLoadResult(filename);
	}
}
