package org.eclipse.jdt.internal.debug.core.model;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IStatusHandler;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.ITerminate;
import org.eclipse.jdt.debug.core.IEvaluationRunnable;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.internal.debug.core.IJDIEventListener;
import org.eclipse.jdt.internal.debug.core.JDIDebugPlugin;
import org.eclipse.jdt.internal.debug.core.StringMatcher;
import org.eclipse.jdt.internal.debug.core.breakpoints.JavaBreakpoint;

import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadGroupReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

/** 
 * Model thread implementation for an underlying
 * thread on a VM.
 */
public class JDIThread extends JDIDebugElement implements IJavaThread {
	/**
	 * Constant for the name of the main thread group.
	 */
	private static final String MAIN_THREAD_GROUP = "main"; //$NON-NLS-1$
	/**
	 * Status code indicating that a request to suspend this thread has timed out
	 */
	public static final int SUSPEND_TIMEOUT= 161;
	/**
	 * Underlying thread.
	 */
	private ThreadReference fThread;
	/**
	 * Collection of stack frames
	 */
	private List fStackFrames;
	/**
	 * Underlying thread group, cached on first access.
	 */
	private ThreadGroupReference fThreadGroup;
	/**
	 * Name of underlying thread group, cached on first access.
	 */
	private String fThreadGroupName;
	/**
	 * Whether children need to be refreshed. Set to
	 * <code>true</code> when stack frames are re-used
	 * on the next suspend.
	 */
	private boolean fRefreshChildren = true;
	/**
	 * Currently pending step handler, <code>null</code>
	 * when not performing a step.
	 */
	private StepHandler fStepHandler= null;
	/**
	 * Whether running.
	 */
	private boolean fRunning;
	/**
	 * Whether terminated.
	 */
	private boolean fTerminated;
	/**
	 * Whether this thread is a system thread.
	 */
	private boolean fIsSystemThread;
	/**
	 * The collection of breakpoints that caused the last suspend, or 
	 * an empty collection if the thread is not suspended or was not
	 * suspended by any breakpoint(s).
	 */
	private List fCurrentBreakpoints = new ArrayList(2);
	/**
	 * Whether this thread is currently performing
	 * an evaluation. An evaluation may involve a series
	 * of method invocations.
	 */
	private boolean fIsPerformingEvaluation= false;
	private IEvaluationRunnable fEvaluationRunnable;
	/**
	 * Whether this thread is currently invoking a method.
	 * Nested method invocations cannot be performed.
	 */
	private boolean fIsInvokingMethod = false;
	/**
	 * Whether or not this thread is currently honoring
	 * breakpoints. This flag allows breakpoints to be
	 * disabled during evaluations.
	 */
	private boolean fHonorBreakpoints= true;
	/**
	 * The kind of step that was originally requested.  Zero or
	 * more 'secondary steps' may be performed programmatically after
	 * the original user-requested step, and this field tracks the
	 * type (step into, over, return) of the original step.
	 */
	private int fOriginalStepKind;
	/**
	 * The JDI Location from which an original user-requested step began.
	 */
	private Location fOriginalStepLocation;
	/**
	 * The total stack depth at the time an original (user-requested) step
	 * is initiated.  This is used along with the original step Location
	 * to determine if a step into comes back to the starting location and
	 * needs to be 'nudged' forward.  Checking the stack depth eliminates 
	 * undesired 'nudging' in recursive methods.
	 */
	private int fOriginalStepStackDepth;
	
	/**
	 * Whether step filters should be used for the next step request.
	 */
	private boolean fUseStepFilters = false;

	/**
	 * Whether or not this thread is currently suspending (user-requested).
	 */
	private boolean fIsSuspending= false;
		
	/**
	 * Creates a new thread on the underlying thread reference
	 * in the given debug target.
	 * 
	 * @param target the debug target in which this thread is contained
	 * @param thread the underlying thread on the VM
	 * @exception ObjectCollectedException if the underlying thread has been
	 * garbage collected and cannot be properly initialized
	 */
	public JDIThread(JDIDebugTarget target, ThreadReference thread) throws ObjectCollectedException {
		super(target);
		setUnderlyingThread(thread);
		initialize();
	}

	/**
	 * Thread initialization:<ul>
	 * <li>Determines if this thread is a system thread</li>
	 * <li>Sets terminated state to <code>false</code></li>
	 * <li>Determines suspended state from underlying thread</li> 
	 * <li>Sets this threads stack frames to an empty collection</li>
	 * </ul>
	 * @exception ObjectCollectedException if the thread has been garbage
	 * collected and cannot be initialized
	 */
	protected void initialize() throws ObjectCollectedException {
		fStackFrames= Collections.EMPTY_LIST;
		// system thread
		try {
			determineIfSystemThread();
		} catch (DebugException e) {
			Throwable underlyingException= e.getStatus().getException();
			if (underlyingException instanceof VMDisconnectedException) {
				// Threads may be created by the VM at shutdown
				// as finalizers. The VM may be disconnected by
				// the time we hear about the thread creation.
				disconnected();
				return;
			}	
			if (underlyingException instanceof ObjectCollectedException) {
				throw (ObjectCollectedException)underlyingException;
			}		
			logError(e);
		}

		// state
		setTerminated(false);
		setRunning(false);
		try {
			setRunning(!getUnderlyingThread().isSuspended());
		} catch (VMDisconnectedException e) {
			disconnected();
			return;
		} catch (ObjectCollectedException e){
			throw e;
		} catch (RuntimeException e) {
			logError(e);
		}
	}

	/**
	 * Adds the given breakpoint to the list of breakpoints
	 * this thread is suspended at
	 */
	protected void addCurrentBreakpoint(IBreakpoint bp) {
		fCurrentBreakpoints.add(bp);
	}
	
	/**
	 * Removes the given breakpoint from the list of breakpoints
	 * this thread is suspended at (called when a breakpoint is
	 * deleted, in case we are suspended at that breakpoint)
	 */
	protected void removeCurrentBreakpoint(IBreakpoint bp) {
		fCurrentBreakpoints.remove(bp);
	}	
	
	/**
	 * @see org.eclipse.debug.core.model.IThread#getBreakpoints()
	 */
	public IBreakpoint[] getBreakpoints() {
		return (IBreakpoint[])fCurrentBreakpoints.toArray(new IBreakpoint[fCurrentBreakpoints.size()]);
	}

	/**
	 * @see ISuspendResume#canResume()
	 */
	public boolean canResume() {
		return isSuspended() && !getDebugTarget().isSuspended();
	}

	/**
	 * @see ISuspendResume#canSuspend()
	 */
	public boolean canSuspend() {
		return !isSuspended();
	}

	/**
	 * @see ITerminate#canTerminate()
	 */
	public boolean canTerminate() {
		return getDebugTarget().canTerminate();
	}

	/**
	 * @see IStep#canStepInto()
	 */
	public boolean canStepInto() {
		return canStep();
	}

	/**
	 * @see IStep#canStepOver()
	 */
	public boolean canStepOver() {
		return canStep();
	}

	/**
	 * @see IStep#canStepReturn()
	 */
	public boolean canStepReturn() {
		return canStep();
	}

	/**
	 * Returns whether this thread is in a valid state to
	 * step.
	 * 
	 * @return whether this thread is in a valid state to
	 * step
	 */
	protected boolean canStep() {
		try {
			return isSuspended() && !isStepping() && getTopStackFrame() != null && !((IJavaDebugTarget)getDebugTarget()).isPerformingHotCodeReplace();
		} catch (DebugException e) {
			return false;
		}
	}

	/**
	 * Determines and sets whether this thread represents a system thread.
	 * 
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected void determineIfSystemThread() throws DebugException {
		fIsSystemThread= false;
		ThreadGroupReference tgr= getUnderlyingThreadGroup();
		fIsSystemThread = tgr != null;
		while (tgr != null) {
			String tgn= null;
			try {
				tgn= tgr.name();
				tgr= tgr.parent();
			} catch (UnsupportedOperationException e) {
				fIsSystemThread = false;
				break;
			} catch (RuntimeException e) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_determining_if_system_thread"), new String[] {e.toString()}), e); //$NON-NLS-1$
				// execution will not reach this line, as
				// #targetRequestFailed will throw an exception				
				return;
			}
			if (tgn != null && tgn.equals(MAIN_THREAD_GROUP)) {
				fIsSystemThread= false;
				break;
			}
		}
	}

	/**
	 * NOTE: this method returns a copy of this thread's stack frames.
	 * 
	 * @see IThread#getStackFrames()
	 */
	public IStackFrame[] getStackFrames() throws DebugException {
		List list = computeStackFrames();
		return (IStackFrame[])list.toArray(new IStackFrame[list.size()]);
	}
	
	/**
	 * @see computeStackFrames()
	 * 
	 * @param refreshChildren whether or not this method should request new stack
	 *        frames from the VM
	 */	
	protected synchronized List computeStackFrames(boolean refreshChildren) throws DebugException {
		if (isSuspended()) {
			if (isTerminated()) {
				fStackFrames = Collections.EMPTY_LIST;
			} else if (refreshChildren) {
				if (fStackFrames.isEmpty()) {
					fStackFrames = createAllStackFrames();
					if (fStackFrames.isEmpty()) {	
						//leave fRefreshChildren == true
						//bug 6393
						return fStackFrames;
					}
				} 
				List frames= getUnderlyingFrames();
				
				boolean topDown = false;
				// what was the last method on the top of the stack
				Method lastMethod = ((JDIStackFrame)fStackFrames.get(0)).getLastMethod();
				// what is the method on top of the stack now
				if (frames.size() > 0) {
					Method currMethod = ((StackFrame)frames.get(0)).location().method();
					if (currMethod.equals(lastMethod)) {
						// preserve frames top down
						topDown = true;					
					}
				}
				
				// compute new or removed stack frames
				int offset= 0, length= frames.size();
				if (length > fStackFrames.size()) {
					if (topDown) {
						// add new (empty) frames to the bottom of the stack to preserve frames top-down
						int num = length - fStackFrames.size();
						for (int i = 0; i < num; i++) {
							fStackFrames.add(new JDIStackFrame(this, null));
						}
					} else {
						// add new frames to the top of the stack, preserve bottom up
						offset= length - fStackFrames.size();
						for (int i= offset - 1; i >= 0; i--) {
							JDIStackFrame newStackFrame= new JDIStackFrame(this, (StackFrame) frames.get(i));
							fStackFrames.add(0, newStackFrame);
						}
						length= fStackFrames.size() - offset;
					}
				} else if (length < fStackFrames.size()) {
					int removed= fStackFrames.size() - length;
					if (topDown) {
						// remove frames from the bottom of the stack, preserve top-down
						for (int i = 0; i < removed; i++) {
							fStackFrames.remove(fStackFrames.size() - 1);
						}
					} else {
						// remove frames from the top of the stack, preserve bottom up
						for (int i= 0; i < removed; i++) {
							fStackFrames.remove(0);
						}
					}
				} else if (length == 0) {
					fStackFrames = Collections.EMPTY_LIST;
				}
				// update preserved frames
				if (offset < fStackFrames.size()) {
					updateStackFrames(frames, offset, length);
				}
			}
			fRefreshChildren = false;
		} else {
			return Collections.EMPTY_LIST;
		}
		return fStackFrames;
	}
	
	/**
	 * Returns this thread's current stack frames as a list, computing
	 * them if required. Returns an empty collection if this thread is
	 * not currently suspended, or this thread is terminated. This
	 * method should be used internally to get the current stack frames,
	 * instead of calling <code>#getStackFrames()</code>, which makes a
	 * copy of the current list.
	 * <p>
	 * Before a thread is resumed a call must be made to one of:<ul>
	 * <li><code>preserveStackFrames()</code></li>
	 * <li><code>disposeStackFrames()</code></li>
	 * </ul>
	 * If stack frames are disposed before a thread is resumed, stack frames
	 * are completely re-computed on the next call to this method. If stack
	 * frames are to be preserved, this method will attempt to re-use any stack
	 * frame objects which represent the same stack frame as on the previous
	 * suspend. Stack frames are cached until a subsequent call to preserve
	 * or dispose stack frames.
	 * </p>
	 * 
	 * @return list of <code>IJavaStackFrame</code>
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */	
	public List computeStackFrames() throws DebugException {
		return computeStackFrames(fRefreshChildren);
	}
	
	/**
	 * @see JDIThread#computeStackFrames()
	 * 
	 * This method differs from computeStackFrames() in that it
	 * always requests new stack frames from the VM. As this is
	 * an expensive operation, this method should only be used
	 * by clients who know for certain that the stack frames
	 * on the VM have changed.
	 */
	public List computeNewStackFrames() throws DebugException {
		return computeStackFrames(true);
	}

	/**
	 * Helper method for <code>#computeStackFrames()</code> to create all
	 * underlying stack frames.
	 * 
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected List createAllStackFrames() throws DebugException {
		List frames= getUnderlyingFrames();
		List list= new ArrayList(frames.size());
		Iterator iter= frames.iterator();
		while (iter.hasNext()) {
			JDIStackFrame newStackFrame= new JDIStackFrame(this, (StackFrame) iter.next());
			list.add(newStackFrame);
		}
		return list;
	}

	/**
	 * Retrieves and returns all underlying stack frames
	 * 
	 * @return list of <code>StackFrame</code>
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected List getUnderlyingFrames() throws DebugException {
		try {
			return getUnderlyingThread().frames();
		} catch (IncompatibleThreadStateException e) {
			requestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_stack_frames"), new String[] {e.toString()}), e, IJavaThread.ERR_THREAD_NOT_SUSPENDED); //$NON-NLS-1$
			// execution will not reach this line, as
			// #targetRequestFailed will thrown an exception			
			return null;
		} catch (RuntimeException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_stack_frames_2"), new String[] {e.toString()}), e); //$NON-NLS-1$
			// execution will not reach this line, as
			// #targetRequestFailed will thrown an exception			
			return null;
		} catch (InternalError e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_stack_frames_2"), new String[] {e.toString()}), e); //$NON-NLS-1$
			// execution will not reach this line, as
			// #targetRequestFailed will thrown an exception			
			return null;
		}
	}

	/**
	 * Returns the underlying method for the given stack frame
	 * 
	 * @param frame an underlying JDI stack frame
	 * @return underlying method
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected Method getUnderlyingMethod(StackFrame frame) throws DebugException {
		try {
			return frame.location().method();
		} catch (RuntimeException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_method"), new String[] {e.toString()}), e); //$NON-NLS-1$
			// execution will not reach this line, as
			// #targetRequestFailed will thrown an exception			
			return null;			
		}
	}

	/**
	 * Returns the number of frames on the stack from the
	 * underlying thread.
	 * 
	 * @return number of frames on the stack
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * <li>This thread is not suspended</li>
	 * </ul>
	 */
	protected int getUnderlyingFrameCount() throws DebugException {
		try {
			return getUnderlyingThread().frameCount();
		} catch (RuntimeException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_frame_count"), new String[] {e.toString()}), e); //$NON-NLS-1$
		} catch (IncompatibleThreadStateException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_frame_count"), new String[] {e.toString()}), e); //$NON-NLS-1$
		}
		// execution will not reach here - try block will either
		// return or exception will be thrown
		return -1;
	}
	
	/**
	 * @see IJavaThread#runEvaluation(IEvaluationRunnable, IProgressMonitor, int)
	 */ 
	public void runEvaluation(IEvaluationRunnable evaluation, IProgressMonitor monitor, int evaluationDetail, boolean hitBreakpoints) throws DebugException {
		if (isPerformingEvaluation()) {
			requestFailed(JDIDebugModelMessages.getString("JDIThread.Cannot_perform_nested_evaluations"), null, IJavaThread.ERR_NESTED_METHOD_INVOCATION); //$NON-NLS-1$			
		}
		
		fIsPerformingEvaluation = true;
		fEvaluationRunnable= evaluation;
		fHonorBreakpoints= hitBreakpoints;
		fireResumeEvent(evaluationDetail);
		try {
			evaluation.run(this, monitor);			
		} catch (DebugException e) {
			throw e;
		} finally {
			fIsPerformingEvaluation = false;
			fEvaluationRunnable= null;
			fHonorBreakpoints= true;
			fireSuspendEvent(evaluationDetail);
		}
	}
	
	/**
	 * @see IJavaThread#terminateEvaluation()
	 */
	public void terminateEvaluation() throws DebugException {
		if (canTerminateEvaluation()) {
			((ITerminate) fEvaluationRunnable).terminate();
		}
	}
	
	/**
	 * @see IJavaThread#canTerminateEvaluation()
	 */
	public boolean canTerminateEvaluation() {
		return fEvaluationRunnable instanceof ITerminate;
	}

	/**
	 * Invokes a method on the target, in this thread, and returns the result. Only
	 * one receiver may be specified - either a class or an object, the other must
	 * be <code>null</code>. This thread is left suspended after the invocation
	 * is complete, unless a call is made to <code>abortEvaluation<code> while
	 * performing a method invocation. In that case, this thread is automatically
	 * resumed when/if this invocation (eventually) completes.
	 * <p>
	 * Method invocations cannot be nested. That is, this method must
	 * return before another call to this method can be made. This
	 * method does not return until the invocation is complete.
	 * Breakpoints can suspend a method invocation, and it is possible
	 * that an invocation will not complete due to an infinite loop
	 * or deadlock.
	 * </p>
	 * <p>
	 * Stack frames are preserved during method invocations, unless
	 * a timeout occurs. Although this thread's state is updated to
	 * running while performing an evaluation, no debug events are
	 * fired unless this invocation is interrupted by a breakpoint,
	 * or the invocation times out.
	 * </p>
	 * <p>
	 * When performing an invocation, the communication timeout with
	 * the target VM is set to infinite, as the invocation may not 
	 * complete in a timely fashion, if at all. The timeout value
	 * is reset to its original value when the invocation completes.
	 * </p>
	 * 
	 * @param receiverClass the class in the target representing the receiver
	 * 	of a static message send, or <code>null</code>
	 * @param receiverObject the object in the target to be the receiver of
	 * 	the message send, or <code>null</code>
	 * @param method the underlying method to be invoked
	 * @param args the arguments to invoke the method with (an empty list
	 *  if none) 
	 * @return the result of the method, as an underlying value
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * <li>This thread is not suspended
	 * 	(status code <code>IJavaThread.ERR_THREAD_NOT_SUSPENDED</code>)</li>
	 * <li>This thread is already invoking a method
	 * 	(status code <code>IJavaThread.ERR_NESTED_METHOD_INVOCATION</code>)</li>
	 * <li>This thread is not suspended by a JDI request
	 * 	(status code <code>IJavaThread.ERR_INCOMPATIBLE_THREAD_STATE</code>)</li>
	 * </ul>
	 */
	protected Value invokeMethod(ClassType receiverClass, ObjectReference receiverObject, Method method, List args, boolean invokeNonvirtual) throws DebugException {
		if (receiverClass != null && receiverObject != null) {
			throw new IllegalArgumentException(JDIDebugModelMessages.getString("JDIThread.can_only_specify_one_receiver_for_a_method_invocation")); //$NON-NLS-1$
		}
		Value result= null;
		int timeout= getRequestTimeout();
		try {
			// this is synchronized such that any other operation that
			// might be resuming this thread has a chance to complete before
			// we determine if it is safe to continue with a method invocation.
			// See bugs 6518, 14069
			synchronized (this) {
				if (!isSuspended()) {
					requestFailed(JDIDebugModelMessages.getString("JDIThread.Evaluation_failed_-_thread_not_suspended"), null, IJavaThread.ERR_THREAD_NOT_SUSPENDED); //$NON-NLS-1$
				}
				if (isInvokingMethod()) {
					requestFailed(JDIDebugModelMessages.getString("JDIThread.Cannot_perform_nested_evaluations"), null, IJavaThread.ERR_NESTED_METHOD_INVOCATION); //$NON-NLS-1$
				}
				// set the request timeout to be infinite
				setRequestTimeout(Integer.MAX_VALUE);
				setRunning(true);
				setInvokingMethod(true);				
			}
			preserveStackFrames();
			int flags= ClassType.INVOKE_SINGLE_THREADED;
			if (invokeNonvirtual) {
				// Superclass method invocation must be performed nonvirtual.
				flags |= ObjectReference.INVOKE_NONVIRTUAL;
			}
			if (receiverClass == null) {
				result= receiverObject.invokeMethod(getUnderlyingThread(), method, args, flags);
			} else {
				result= receiverClass.invokeMethod(getUnderlyingThread(), method, args, flags);
			}
		} catch (InvalidTypeException e) {
			invokeFailed(e, timeout);
		} catch (ClassNotLoadedException e) {
			invokeFailed(e, timeout);
		} catch (IncompatibleThreadStateException e) {
			invokeFailed(JDIDebugModelMessages.getString("JDIThread.Thread_must_be_suspended_by_step_or_breakpoint_to_perform_method_invocation_1"), IJavaThread.ERR_INCOMPATIBLE_THREAD_STATE, e, timeout); //$NON-NLS-1$
		} catch (InvocationException e) {
			invokeFailed(e, timeout);
		} catch (RuntimeException e) {
			invokeFailed(e, timeout);
		}

		invokeComplete(timeout);
		return result;
	}
	
	/**
	 * Invokes a constructor in this thread, creating a new instance of the given
	 * class, and returns the result as an object reference.
	 * This thread is left suspended after the invocation
	 * is complete.
	 * <p>
	 * Method invocations cannot be nested. That is, this method must
	 * return before another call to this method can be made. This
	 * method does not return until the invocation is complete.
	 * Breakpoints can suspend a method invocation, and it is possible
	 * that an invocation will not complete due to an infinite loop
	 * or deadlock.
	 * </p>
	 * <p>
	 * Stack frames are preserved during method invocations, unless
	 * a timeout occurs. Although this thread's state is updated to
	 * running while performing an evaluation, no debug events are
	 * fired unless this invocation is interrupted by a breakpoint,
	 * or the invocation times out.
	 * </p>
	 * <p>
	 * When performing an invocation, the communication timeout with
	 * the target VM is set to infinite, as the invocation may not 
	 * complete in a timely fashion, if at all. The timeout value
	 * is reset to its original value when the invocation completes.
	 * </p>
	 * 
	 * @param receiverClass the class in the target representing the receiver
	 * 	of the 'new' message send
	 * @param constructor the underlying constructor to be invoked
	 * @param args the arguments to invoke the constructor with (an empty list
	 *  if none) 
	 * @return a new object reference
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected ObjectReference newInstance(ClassType receiverClass, Method constructor, List args) throws DebugException {
		if (isInvokingMethod()) {
			requestFailed(JDIDebugModelMessages.getString("JDIThread.Cannot_perform_nested_evaluations_2"), null); //$NON-NLS-1$
		}
		ObjectReference result= null;
		int timeout= getRequestTimeout();
		try {
			// set the request timeout to be infinite
			setRequestTimeout(Integer.MAX_VALUE);
			setRunning(true);
			setInvokingMethod(true);
			preserveStackFrames();
			result= receiverClass.newInstance(getUnderlyingThread(), constructor, args, ClassType.INVOKE_SINGLE_THREADED);
		} catch (InvalidTypeException e) {
			invokeFailed(e, timeout);
		} catch (ClassNotLoadedException e) {
			invokeFailed(e, timeout);
		} catch (IncompatibleThreadStateException e) {
			invokeFailed(e, timeout);
		} catch (InvocationException e) {
			invokeFailed(e, timeout);
		} catch (RuntimeException e) {
			invokeFailed(e, timeout);
		}

		invokeComplete(timeout);
		return result;
	}
	
	/**
	 * Called when an invocation fails. Performs cleanup
	 * and throws an exception.
	 * 
	 * @param e the exception that caused the failure
	 * @param restoreTimeout the communication timeout value,
	 * 	in milliseconds, that should be reset
	 * @see #invokeComplete(int)
	 * @exception DebugException.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected void invokeFailed(Throwable e, int restoreTimeout) throws DebugException {
		invokeFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_invoking_method"), new String[] {e.toString()}), DebugException.TARGET_REQUEST_FAILED, e, restoreTimeout); //$NON-NLS-1$
	}
	
	/**
	 * Called when an invocation fails. Performs cleanup
	 * and throws an exception.
	 * 
	 * @param message error message
	 * @param code status code
	 * @param e the exception that caused the failure
	 * @param restoreTimeout the communication timeout value,
	 * 	in milliseconds, that should be reset
	 * @see #invokeComplete(int)
	 * @exception DebugException.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected void invokeFailed(String message, int code, Throwable e, int restoreTimeout) throws DebugException {
		invokeComplete(restoreTimeout);
		requestFailed(message, e, code);
	}	
	
	/**
	 * Called when a method invocation has returned, successfully
	 * or not. This method performs cleanup:<ul>
	 * <li>Resets the state of this thread to suspended</li>
	 * <li>Restores the communication timeout value</li>
	 * <li>Computes the new set of stack frames for this thread</code>
	 * </ul>
	 * 
	 * @param restoreTimeout the communication timeout value,
	 * 	in milliseconds, that should be reset
	 * @see #invokeMethod(ClassType, ObjectReference, Method, List)
 	 * @see #newInstance(ClassType, Method, List)
	 */
	protected void invokeComplete(int restoreTimeout) {
		abortStep();
		setRunning(false);
		setInvokingMethod(false);
		setRequestTimeout(restoreTimeout);
		// update preserved stack frames
		try {
			computeStackFrames();
		} catch (DebugException e) {
			logError(e);
		}
	}
	
	/**
	 * @see IThread#getName()
	 */
	public String getName() throws DebugException {
		try {
			return getUnderlyingThread().name();
		} catch (RuntimeException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_thread_name"), new String[] {e.toString()}), e); //$NON-NLS-1$
			// execution will not fall through, as
			// #targetRequestFailed will thrown an exception
		}
		return null;
	}

	/**
	 * @see IThread#getPriority
	 */
	public int getPriority() throws DebugException {
		// to get the priority, we must get the value from the "priority" field
		Field p= null;
		try {
			p= getUnderlyingThread().referenceType().fieldByName("priority"); //$NON-NLS-1$
			if (p == null) {
				requestFailed(JDIDebugModelMessages.getString("JDIThread.no_priority_field"), null); //$NON-NLS-1$
			}
			Value v= getUnderlyingThread().getValue(p);
			if (v instanceof IntegerValue) {
				return ((IntegerValue)v).value();
			} else {
				requestFailed(JDIDebugModelMessages.getString("JDIThread.priority_not_an_integer"), null); //$NON-NLS-1$
			}
		} catch (RuntimeException e) {
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_thread_priority"), new String[] {e.toString()}), e); //$NON-NLS-1$
		}
		// execution will not fall through to this line, as
		// #targetRequestFailed or #requestFailed will throw
		// an exception		
		return -1;
	}

	/**
	 * @see IThread#getTopStackFrame()
	 */
	public IStackFrame getTopStackFrame() throws DebugException {
		List c= computeStackFrames();
		if (c.isEmpty()) {
			return null;
		} else {
			return (IStackFrame) c.get(0);
		}
	}

	/**
	 * A breakpoint has suspended execution of this thread.
	 * Aborts any step currently in process and fires a
	 * suspend event.
	 * 
	 * @param breakpoint the breakpoint that caused the suspend
	 * @return whether this thread suspended
	 */
	public boolean handleSuspendForBreakpoint(JavaBreakpoint breakpoint, boolean queueEvent) {
		addCurrentBreakpoint(breakpoint);
		try {
			// update state to suspended but don't actually
			// suspend unless a registered listener agrees
			if (breakpoint.getSuspendPolicy() == IJavaBreakpoint.SUSPEND_VM) {
				((JDIDebugTarget)getDebugTarget()).prepareToSuspendByBreakpoint(breakpoint);
			} else {
				setRunning(false);
			}
			
			// poll listeners
			boolean suspend = JDIDebugPlugin.getDefault().fireBreakpointHit(this, breakpoint);
			
			// suspend or resume
			if (suspend) {
				if (breakpoint.getSuspendPolicy() == IJavaBreakpoint.SUSPEND_VM) {
					((JDIDebugTarget)getDebugTarget()).suspendedByBreakpoint(breakpoint, queueEvent);
				}
				abortStep();
				if (queueEvent) {
					queueSuspendEvent(DebugEvent.BREAKPOINT);
				} else {
					fireSuspendEvent(DebugEvent.BREAKPOINT);
				}
			} else {
				if (breakpoint.getSuspendPolicy() == IJavaBreakpoint.SUSPEND_VM) {
					((JDIDebugTarget)getDebugTarget()).cancelSuspendByBreakpoint(breakpoint);
				} else {
					setRunning(true);
					// dispose cached stack frames so we re-retrieve on the next breakpoint
					preserveStackFrames();
				}				
			}
			return suspend;
		} catch (CoreException e) {
			logError(e);
			return true;
		}
	}
	
	/**
	 * Updates the state of this thread to suspend for
	 * the given breakpoint  but does not fire notification
	 * of the suspend.
	 */
	public boolean handleSuspendForBreakpointQuiet(JavaBreakpoint breakpoint) {
		addCurrentBreakpoint(breakpoint);
		setRunning(false);
		abortStep();
		return true;
	}

	/**
	 * @see IStep#isStepping()
	 */
	public boolean isStepping() {
		return getPendingStepHandler() != null;
	}

	/**
	 * @see ISuspendResume#isSuspended()
	 */
	public boolean isSuspended() {
		return !fRunning && !fTerminated;
	}

	/**
	 * @see IJavaThread#isSystemThread()
	 */
	public boolean isSystemThread() {
		return fIsSystemThread;
	}

	/**
	 * @see IJavaThread#getThreadGroupName()
	 */
	public String getThreadGroupName() throws DebugException {
		if (fThreadGroupName == null) {
			ThreadGroupReference tgr= getUnderlyingThreadGroup();
			
			// bug# 20370
			if (tgr == null) {
				return null;
			}
			
			try {
				fThreadGroupName = tgr.name();
			} catch (RuntimeException e) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_thread_group_name"), new String[] {e.toString()}), e); //$NON-NLS-1$
				// execution will not reach this line, as
				// #targetRequestFailed will thrown an exception
				return null;
			}
		}
		return fThreadGroupName;
	}

	/**
	 * @see ITerminate#isTerminated()
	 */
	public boolean isTerminated() {
		return fTerminated;
	}
	
	public boolean isOutOfSynch() throws DebugException {
		if (isSuspended() && ((JDIDebugTarget)getDebugTarget()).hasHCRFailed()) {
			List frames= computeStackFrames();
			Iterator iter= frames.iterator();
			while (iter.hasNext()) {
				if (((JDIStackFrame) iter.next()).isOutOfSynch()) {
					return true;
				}
			}
			return false;
		} else {
			// If the thread is not suspended, there's no way to 
			// say for certain that it is running out of synch code
			return false;
		}
	}
	
	public boolean mayBeOutOfSynch() throws DebugException {
		if (!isSuspended()) {
			return ((JDIDebugTarget)getDebugTarget()).hasHCRFailed();
		}
		return false;
	}	
	
	/**
	 * Sets whether this thread is terminated
	 * 
	 * @param terminated whether this thread is terminated
	 */
	protected void setTerminated(boolean terminated) {
		fTerminated= terminated;
	}

	/**
	 * @see ISuspendResume#resume()
	 */
	public void resume() throws DebugException {
		resumeThread(true);
	}
	
	/**
	 * @see ISuspendResume#resume()
	 * 
	 * Updates the state of this thread to resumed,
	 * but does not fire notification of the resumption.
	 */
	public void resumeQuiet() throws DebugException {
		resumeThread(false);
	}
	
	/**
	 * @see ISuspendResume#resume()
	 * 
	 * Updates the state of this thread, but only fires
	 * notification to listeners if <code>fireNotification</code>
	 * is <code>true</code>.
	 */
	private void resumeThread(boolean fireNotification) throws DebugException {
		if (!isSuspended()) {
			return;
		}
		try {
			setRunning(true);
			preserveStackFrames();
			if (fireNotification) {
				fireResumeEvent(DebugEvent.CLIENT_REQUEST);
			}
			getUnderlyingThread().resume();
		} catch (VMDisconnectedException e) {
			disconnected();
		} catch (RuntimeException e) {
			setRunning(false);
			fireSuspendEvent(DebugEvent.CLIENT_REQUEST);
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_resuming"), new String[] {e.toString()}), e); //$NON-NLS-1$
		}
	}
		
	/**
	 * Sets whether this thread is currently executing.
	 * When set to <code>true</code>, this thread's current
	 * breakpoints are cleared.
	 * 
	 * @param running whether this thread is executing
	 */
	protected void setRunning(boolean running) {
		fRunning = running;
		if (running) {
			fCurrentBreakpoints.clear();
		} 
	}

	/**
	 * Preserves stack frames to be used on the next suspend event.
	 * Iterates through all current stack frames, setting their
	 * state as invalid. This method should be called before this thread
	 * is resumed, when stack frames are to be re-used when it later
	 * suspends.
	 * 
	 * @see computeStackFrames()
	 */
	protected synchronized void preserveStackFrames() {
		fRefreshChildren = true;
		Iterator frames = fStackFrames.iterator();
		while (frames.hasNext()) {
			((JDIStackFrame)frames.next()).setUnderlyingStackFrame(null);
		}
	}

	/**
	 * Disposes stack frames, to be completely re-computed on
	 * the next suspend event. This method should be called before
	 * this thread is resumed when stack frames are not to be re-used
	 * on the next suspend.
	 * 
	 * @see computeStackFrames()
	 */
	protected synchronized void disposeStackFrames() {
		fStackFrames= Collections.EMPTY_LIST;
		fRefreshChildren = true;
	}
	
	/**
	 * This method is synchronized, such that the step request
	 * begins before a background evaluation can be performed.
	 * 
	 * @see IStep#stepInto()
	 */
	public synchronized void stepInto() throws DebugException {
		if (!canStepInto()) {
			return;
		}
		StepHandler handler = new StepIntoHandler();
		handler.step();
	}

	/** 
	 * This method is synchronized, such that the step request
	 * begins before a background evaluation can be performed.
	 * 
	 * @see IStep#stepOver()
	 */
	public synchronized void stepOver() throws DebugException {
		if (!canStepOver()) {
			return;
		}
		StepHandler handler = new StepOverHandler();
		handler.step();
	}

	/**
	 * This method is synchronized, such that the step request
	 * begins before a background evaluation can be performed.
	 * 
	 * @see IStep#stepReturn()
	 */
	public synchronized void stepReturn() throws DebugException {
		if (!canStepReturn()) {
			return;
		}
		StepHandler handler = new StepReturnHandler();
		handler.step();
	}
	
	protected void setOriginalStepKind(int stepKind) {
		fOriginalStepKind = stepKind;
	}
	
	protected int getOriginalStepKind() {
		return fOriginalStepKind;
	}
	
	protected void setOriginalStepLocation(Location location) {
		fOriginalStepLocation = location;
	}
	
	protected Location getOriginalStepLocation() {
		return fOriginalStepLocation;
	}
	
	protected void setOriginalStepStackDepth(int depth) {
		fOriginalStepStackDepth = depth;
	}
	
	protected int getOriginalStepStackDepth() {
		return fOriginalStepStackDepth;
	}
	
	/**
	 * In cases where a user-requested step into encounters nothing but filtered code
	 * (static initializers, synthetic methods, etc.), the default JDI behavior is to
	 * put the instruction pointer back where it was before the step into.  This requires
	 * a second step to move forward.  Since this is confusing to the user, we do an 
	 * extra step into in such situations.  This method determines when such an extra 
	 * step into is necessary.  It compares the current Location to the original
	 * Location when the user step into was initiated.  It also makes sure the stack depth
	 * now is the same as when the step was initiated.
	 */
	protected boolean shouldDoExtraStepInto(Location location) throws DebugException {
		if (getOriginalStepKind() != StepRequest.STEP_INTO) {
			return false;
		}
		if (getOriginalStepStackDepth() != getUnderlyingFrameCount()) {
			return false;
		}
		Location origLocation = getOriginalStepLocation();
		if (origLocation == null) {
			return false;
		}	
		// We cannot simply check if the two Locations are equal using the equals()
		// method, since this checks the code index within the method.  Even if the
		// code indices are different, the line numbers may be the same, in which case
		// we need to do the extra step into.
		Method origMethod = origLocation.method();
		Method currMethod = location.method();
		if (!origMethod.equals(currMethod)) {
			return false;
		}	
		if (origLocation.lineNumber() != location.lineNumber()) {
			return false;
		}			
		return true;
	}
	
	/**
	 * @see ISuspendResume#suspend()
	 */
	public void suspend() throws DebugException {
		try {
			// Abort any pending step request
			abortStep();
			suspendUnderlyingThread();
		} catch (RuntimeException e) {
			setRunning(true);
			targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_suspending"), new String[] {e.toString()}), e); //$NON-NLS-1$
		}
	}
	
	/**
	 * Suspends the underlying thread asynchronously and fires notification when
	 * the underlying thread is suspended.
	 */
	protected void suspendUnderlyingThread() {
		if (fIsSuspending) {
			return;
		}		
		fIsSuspending= true;
		Thread thread= new Thread(new Runnable() {
			public void run() {
				try {
					getUnderlyingThread().suspend();
					int timeout= JDIDebugModel.getPreferences().getInt(JDIDebugModel.PREF_REQUEST_TIMEOUT);
					long stop= System.currentTimeMillis() + timeout;
					boolean suspended= isUnderlyingThreadSuspended();
					while (System.currentTimeMillis() < stop && !suspended) {
						try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
						}
						suspended= isUnderlyingThreadSuspended();
						if (suspended) {
							break;
						}
					}
					if (!suspended) {
						IStatus status= new Status(IStatus.ERROR, JDIDebugPlugin.getUniqueIdentifier(), SUSPEND_TIMEOUT, MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.suspend_timeout"), new String[] {new Integer(timeout).toString()}), null); //$NON-NLS-1$
						IStatusHandler handler= DebugPlugin.getDefault().getStatusHandler(status);
						if (handler != null) {
							try {
								handler.handleStatus(status, JDIThread.this);
							} catch (CoreException e) {
							}
						}
					}
					setRunning(false);
					fireSuspendEvent(DebugEvent.CLIENT_REQUEST);
				} catch (RuntimeException exception) {
				} finally {
					fIsSuspending= false;
				}
			}
		});
		thread.start();
	}
	
	public boolean isUnderlyingThreadSuspended() {
		return getUnderlyingThread().isSuspended();
	}

	/**
	 * Notifies this thread that it has been suspended due
	 * to a VM suspend.
	 */	
	protected void suspendedByVM() {
		setRunning(false);
	}

	/**
	 * Notifies this thread that is has been resumed due
	 * to a VM resume.
	 */
	protected void resumedByVM() {
		setRunning(true);
		preserveStackFrames();
	}

	/**
	 * @see ITerminate#terminate()
	 */
	public void terminate() throws DebugException {
		getDebugTarget().terminate();
	}

	/**
	 * Replaces the underlying stack frame objects in the preserved frames
	 * list with the current underlying stack frames.
	 * 
	 * @param newFrames list of current underlying <code>StackFrame</code>s.
	 * 	Frames from this list are assigned to the underlying frames in
	 *  the <code>oldFrames</code> list.
	 * @param offset the offset in the lists at which to start replacing
	 *  the old underlying frames
	 * @param length the number of frames to replace
	 */
	protected void updateStackFrames(List newFrames, int offset, int length) throws DebugException {
		JDIStackFrame oldFrame;
		StackFrame newFrame;
		for (int i= 0; i < length; i++) {
			oldFrame= (JDIStackFrame) fStackFrames.get(offset);
			newFrame= (StackFrame) newFrames.get(offset);
			if (oldFrame.getLastMethod() != null && oldFrame.getLastMethod().equals(newFrame.location().method())) {
				oldFrame.setUnderlyingStackFrame(newFrame);
			} else {
				fStackFrames.set(offset, new JDIStackFrame(this, newFrame));
			}
			offset++;
		}
	}

	/**
	 * Drops to the given stack frame
	 * 
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected void dropToFrame(IStackFrame frame) throws DebugException {
		JDIDebugTarget target= (JDIDebugTarget) getDebugTarget();
		if (target.canPopFrames()) {
			// JDK 1.4 support
			try {
				// Pop the drop frame and all frames above it
				popFrame(frame);
				stepInto();
			} catch (RuntimeException exception) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_dropping_to_frame"), new String[] {exception.toString()}),exception); //$NON-NLS-1$
			}
		} else {
			// J9 support
			// This block is synchronized, such that the step request
	 		// begins before a background evaluation can be performed.
			synchronized (this) {
				StepHandler handler = new DropToFrameHandler(frame);
				handler.step();
			}
		}
	}
	
	protected void popFrame(IStackFrame frame) throws DebugException {
		JDIDebugTarget target= (JDIDebugTarget)getDebugTarget();
		if (target.canPopFrames()) {
			// JDK 1.4 support
			try {
				// Pop the frame and all frames above it
				StackFrame jdiFrame= null;
				int desiredSize= fStackFrames.size() - fStackFrames.indexOf(frame) - 1;
				int lastSize= fStackFrames.size() + 1; // Set up to pass the first test
				int size= fStackFrames.size();
				while (size < lastSize && size > desiredSize) {
					// Keep popping frames until the stack stops getting smaller
					// or popFrame is gone.
					// see Bug 8054
					jdiFrame = ((JDIStackFrame) frame).getUnderlyingStackFrame();
					preserveStackFrames();
					fThread.popFrames(jdiFrame);
					lastSize= size;
					size= computeStackFrames().size();
				}
			} catch (IncompatibleThreadStateException exception) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_popping"), new String[] {exception.toString()}),exception); //$NON-NLS-1$
			} catch (InvalidStackFrameException exception) {
				// InvalidStackFrameException can be thrown when all but the
				// deepest frame were popped. Fire a changed notification
				// in case this has occured.
				fireChangeEvent(DebugEvent.CONTENT);
				targetRequestFailed(exception.toString(),exception); //$NON-NLS-1$
			} catch (RuntimeException exception) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_popping"), new String[] {exception.toString()}),exception); //$NON-NLS-1$
			}
		}
	}
	
	/**
	 * Steps until the specified stack frame is the top frame. Provides
	 * ability to step over/return in the non-top stack frame.
	 * This method is synchronized, such that the step request
	 * begins before a background evaluation can be performed.
	 * 
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * </ul>
	 */
	protected synchronized void stepToFrame(IStackFrame frame) throws DebugException {
		if (!canStepReturn()) {
			return;
		}
		StepHandler handler = new StepToFrameHandler(frame);
		handler.step();
	}
		
	/**
	 * Aborts the current step, if any.
	 */
	protected void abortStep() {
		StepHandler handler = getPendingStepHandler();
		if (handler != null) {
			handler.abort();
		}
	}

	/**
	 * @see IJavaThread#findVariable(String)
	 */
	public IJavaVariable findVariable(String varName) throws DebugException {
		if (isSuspended()) {
			try {
				Iterator stackFrames= computeStackFrames().iterator();
				while (stackFrames.hasNext()) {
					JDIStackFrame sf= (JDIStackFrame)stackFrames.next();
					IJavaVariable var= sf.findVariable(varName);
					if (var != null) {
						return var;
					}
				}
			} catch (DebugException e) {
				// if the thread has since reusmed, return null (no need to report error)
				if (e.getStatus().getCode() != IJavaThread.ERR_THREAD_NOT_SUSPENDED) {
					throw e;
				}
			}
		}
		return null;
	}
		
	/**
	 * Notification this thread has terminated - update state
	 * and fire a terminate event.
	 */
	protected void terminated() {
		setTerminated(true);
		setRunning(false);	
		fireTerminateEvent();
	}
	
	/** 
	 * Returns this thread on the underlying VM which this
	 * model thread is a proxy to.
	 * 
	 * @return underlying thread
	 */
	public ThreadReference getUnderlyingThread() {
		return fThread;
	}
	
	/**
	 * Sets the underlying thread that this model object
	 * is a proxy to.
	 * 
	 * @param thread underlying thread on target VM
	 */
	protected void setUnderlyingThread(ThreadReference thread) {
		fThread = thread;
	}
	
	/** 
	 * Returns this thread's underlying thread group.
	 * 
	 * @return thread group
	 * @exception DebugException if this method fails.  Reasons include:
	 * <ul>
	 * <li>Failure communicating with the VM.  The DebugException's
	 * status code contains the underlying exception responsible for
	 * the failure.</li>
	 * <li>Retrieving the underlying thread group is not supported
	 * on the underlying VM</li>
	 * </ul>
	 */
	protected ThreadGroupReference getUnderlyingThreadGroup() throws DebugException {
		if (fThreadGroup == null) {
			try {
				fThreadGroup = getUnderlyingThread().threadGroup();
			} catch (UnsupportedOperationException e) {
				requestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_thread_group"), new String[] {e.toString()}), e); //$NON-NLS-1$
				// execution will not reach this line, as
				// #requestFailed will throw an exception				
				return null;
			} catch (RuntimeException e) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_retrieving_thread_group"), new String[] {e.toString()}), e); //$NON-NLS-1$
				// execution will not reach this line, as
				// #targetRequestFailed will throw an exception				
				return null;
			}
		}
		return fThreadGroup;
	}
		 	
	/**
	 * @see IJavaThread#isPerformingEvaluation()
	 */
	public boolean isPerformingEvaluation() {
		return fIsPerformingEvaluation;
	}
	
	/**
	 * Returns whether this thread is currently performing
	 * a method invokation 
	 */
	public boolean isInvokingMethod() {
		return fIsInvokingMethod;
	}
	
	/**
	 * Returns whether this thread is currently ignoring
	 * breakpoints.
	 */
	public boolean isIgnoringBreakpoints() {
		return !fHonorBreakpoints;
	}
	
	/**
	 * Sets whether this thread is currently invoking a method
	 * 
	 * @param evaluating whether this thread is currently
	 *  invoking a method
	 */
	protected void setInvokingMethod(boolean invoking) {
		fIsInvokingMethod= invoking;
	}
	
	/**
	 * Sets the step handler currently handling a step
	 * request.
	 * 
	 * @param handler the current step handler, or <code>null</code>
	 * 	if none
	 */
	protected void setPendingStepHandler(StepHandler handler) {
		fStepHandler = handler;
	}
	
	/**
	 * Returns the step handler currently handling a step
	 * request, or <code>null</code> if none.
	 * 
	 * @return step handler, or <code>null</code> if none
	 */
	protected StepHandler getPendingStepHandler() {
		return fStepHandler;
	}
	
	
	/**
	 * Helper class to perform stepping an a thread.
	 */
	abstract class StepHandler implements IJDIEventListener {
		/**
		 * Request for stepping in the underlying VM
		 */
		private StepRequest fStepRequest;
		
		/**
		 * Initiates a step in the underlying VM by creating a step
		 * request of the appropriate kind (over, into, return),
		 * and resuming this thread. When a step is initiated it
		 * is registered with its thread as a pending step. A pending
		 * step could be cancelled if a breakpoint suspends execution
		 * during the step.
		 * <p>
		 * This thread's state is set to running and stepping, and
		 * stack frames are invalidated (but preserved to be re-used
		 * when the step completes). A resume event with a step detail
		 * is fired for this thread.
		 * </p>
		 * Note this method does nothing if this thread has no stack frames.
		 * 
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected void step() throws DebugException {
			JDIStackFrame top = (JDIStackFrame)getTopStackFrame();
			if (top == null) {
				return;
			}
			setOriginalStepKind(getStepKind());
			Location location = top.getUnderlyingStackFrame().location();
			setOriginalStepLocation(location);
			setOriginalStepStackDepth(computeStackFrames().size());
			setStepRequest(createStepRequest());
			setPendingStepHandler(this);
			addJDIEventListener(this, getStepRequest());
			setRunning(true);
			preserveStackFrames();
			fireResumeEvent(getStepDetail());
			invokeThread();
		}
		
		/**
		 * Resumes the underlying thread to initiate the step.
		 * By default the thread is resumed. Step handlers that
		 * require other actions can override this method.
		 * 
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected void invokeThread() throws DebugException {
			try {
				getUnderlyingThread().resume();
			} catch (RuntimeException e) {
				stepEnd();
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_stepping"), new String[] {e.toString()}), e); //$NON-NLS-1$
			}
		}
		
		/**
		 * Creates and returns a step request specific to this step
		 * handler. Subclasses must override <code>getStepKind()</code>
		 * to return the kind of step it implements.
		 * 
		 * @return step request
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected StepRequest createStepRequest() throws DebugException {
			try {
				StepRequest request = getEventRequestManager().createStepRequest(getUnderlyingThread(), StepRequest.STEP_LINE, getStepKind());
				request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
				request.addCountFilter(1);
				attachFiltersToStepRequest(request);
				request.enable();
				return request;
			} catch (RuntimeException e) {
				targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_creating_step_request"), new String[] {e.toString()}), e); //$NON-NLS-1$
			}			
			// this line will never be executed, as the try block
			// will either return, or the catch block will throw 
			// an exception
			return null;
			
		}
		
		/**
		 * Returns the kind of step this handler implements.
		 * 
		 * @return one of <code>StepRequest.STEP_INTO</code>,
		 * 	<code>StepRequest.STEP_OVER</code>, <code>StepRequest.STEP_OUT</code>
		 */
		protected abstract int getStepKind();
		
		/**
		 * Returns the detail for this step event.
		 * 
		 * @return one of <code>DebugEvent.STEP_INTO</code>,
		 * 	<code>DebugEvent.STEP_OVER</code>, <code>DebugEvent.STEP_RETURN</code>
		 */
		protected abstract int getStepDetail();		
		
		/**
		 * Sets the step request created by this handler in
		 * the underlying VM. Set to <code>null<code> when
		 * this handler deletes its request.
		 * 
		 * @param request step request
		 */
		protected void setStepRequest(StepRequest request) {
			fStepRequest = request; 
		}
		
		/**
		 * Returns the step request created by this handler in
		 * the underlying VM.
		 * 
		 * @return step request
		 */
		protected StepRequest getStepRequest() {
			return fStepRequest;
		}
		
		/**
		 * Deletes this handler's step request from the underlying VM
		 * and removes this handler as an event listener.
		 */
		protected void deleteStepRequest() {
			removeJDIEventListener(this, getStepRequest());
			try {
				getEventRequestManager().deleteEventRequest(getStepRequest());
				setStepRequest(null);
			} catch (RuntimeException e) {
				logError(e);
			}
		}
		
		/**
		 * If step filters are currently switched on and the current location is not a filtered
		 * location, set all active filters on the step request.
		 */
		protected void attachFiltersToStepRequest(StepRequest request) {
			
			if (applyStepFilters() && (getJavaDebugTarget().isStepFiltersEnabled() || fUseStepFilters)) {
				Location currentLocation= getOriginalStepLocation();
				if (currentLocation == null) {
					return;
				}
				//check if the user has already stopped in a filtered location
				//is so do not filter @see bug 5587
				ReferenceType type= currentLocation.declaringType();
				String typeName= type.name();
				String[] activeFilters = getJavaDebugTarget().getStepFilters();
				for (int i = 0; i < activeFilters.length; i++) {
					StringMatcher matcher = new StringMatcher(activeFilters[i], false, false);
					if (matcher.match(typeName)) {
						return;
					}
				}
				for (int i = 0; i < activeFilters.length; i++) {
					request.addClassExclusionFilter(activeFilters[i]);
				}
			}
		}
		
		/**
		 * Returns whether this step handler should use step
		 * filters when creating its step request. By default,
		 * step filters are not used. Subclasses must override 
		 * if/when required.
		 * 
		 * @return whether this step handler should use step
		 * filters when creating its step request
		 */
		protected boolean applyStepFilters() {
			return false;
		}
		
		/**
		 * Notification the step request has completed.
		 * If the current location matches one of the user-specified
		 * step filter criteria (e.g., synthetic methods, static initializers),
		 * then continue stepping.
		 * 
		 * @see IJDIEventListener#handleEvent(Event, JDIDebugTarget)
		 */
		public boolean handleEvent(Event event, JDIDebugTarget target) {
			try {
				StepEvent stepEvent = (StepEvent) event;
				Location currentLocation = stepEvent.location();

				// if the ending step location is filtered and we did not start from
				// a filtered location, or if we're back where
				// we started on a step into, do another step of the same kind
				if (locationShouldBeFiltered(currentLocation) || shouldDoExtraStepInto(currentLocation)) {
					setRunning(true);
					deleteStepRequest();
					createSecondaryStepRequest();			
					return true;		
				// otherwise, we're done stepping
				} else {
					stepEnd();
					return false;
				}
			} catch (DebugException e) {
				logError(e);
				stepEnd();
				return false;
			}
		}
		
		/**
		 * Returns <code>true</code> if the StepEvent's Location is a Method that the 
		 * user has indicated (via the step filter preferences) should be 
		 * filtered and the step was not initiated from a filtered location.
		 * Returns <code>false</code> otherwise.
		 */
		protected boolean locationShouldBeFiltered(Location location) throws DebugException {
			if (applyStepFilters()) {
				Location origLocation= getOriginalStepLocation();
				if (origLocation != null) {
					return !locationIsFiltered(origLocation.method()) && locationIsFiltered(location.method());
				}
			}
			return false;
		}
		/**
		 * Returns <code>true</code> if the StepEvent's Location is a Method that the 
		 * user has indicated (via the step filter preferences) should be 
		 * filtered.  Returns <code>false</code> otherwise.
		 */
		protected boolean locationIsFiltered(Method method) {
			if (getJavaDebugTarget().isStepFiltersEnabled() || fUseStepFilters) {
				boolean filterStatics = getJavaDebugTarget().isFilterStaticInitializers();
				boolean filterSynthetics = getJavaDebugTarget().isFilterSynthetics();
				boolean filterConstructors = getJavaDebugTarget().isFilterConstructors();
				if (!(filterStatics || filterSynthetics || filterConstructors)) {
					return false;
				}			
				
				if ((filterStatics && method.isStaticInitializer()) ||
					(filterSynthetics && method.isSynthetic()) ||
					(filterConstructors && method.isConstructor()) ) {
					return true;	
				}
			}
			
			return false;
		}

		/**
		 * Cleans up when a step completes.<ul>
		 * <li>Thread state is set to suspended.</li>
		 * <li>Stepping state is set to false</li>
		 * <li>Stack frames and variables are incrementally updated</li>
		 * <li>The step request is deleted and removed as
		 * 		and event listener</li>
		 * <li>A suspend event is fired</li>
		 * </ul>
		 */
		protected void stepEnd() {
			fUseStepFilters = false;
			setRunning(false);
			deleteStepRequest();
			setPendingStepHandler(null);
			queueSuspendEvent(DebugEvent.STEP_END);
		}
		
		/**
		 * Creates another step request in the underlying thread of the
		 * appropriate kind (over, into, return). This thread will
		 * be resumed by the event dispatcher as this event handler
		 * will vote to resume suspended threads. When a step is
		 * initiated it is registered with its thread as a pending
		 * step. A pending step could be cancelled if a breakpoint
		 * suspends execution during the step.
		 * 
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected void createSecondaryStepRequest() throws DebugException {
			setStepRequest(createStepRequest());
			setPendingStepHandler(this);
			addJDIEventListener(this, getStepRequest());			
		}	
		
		/**
		 * Aborts this step request if active. The step event
		 * request is deleted from the underlying VM.
		 */
		protected void abort() {
			if (getStepRequest() != null) {
				deleteStepRequest();
				setPendingStepHandler(null);
			}
		}
}
	
	/**
	 * Handler for step over requests.
	 */
	class StepOverHandler extends StepHandler {
		/**
		 * @see StepHandler#getStepKind()
		 */
		protected int getStepKind() {
			return StepRequest.STEP_OVER;
		}	
		
		/**
		 * @see StepHandler#getStepDetail()
		 */
		protected int getStepDetail() {
			return DebugEvent.STEP_OVER;
		}		
	}
	
	/**
	 * Handler for step into requests.
	 */
	class StepIntoHandler extends StepHandler {
		/**
		 * @see StepHandler#getStepKind()
		 */
		protected int getStepKind() {
			return StepRequest.STEP_INTO;
		}	
		
		/**
		 * @see StepHandler#getStepDetail()
		 */
		protected int getStepDetail() {
			return DebugEvent.STEP_INTO;
		}
		
		/**
		 * Returns <code>true</code>. Step filters are applied for
		 * stepping into new frames.
		 * 
		 * @see StepHandler#applyStepFilters()
		 */
		protected boolean applyStepFilters() {
			return true;
		}
	}
	
	/**
	 * Handler for step return requests.
	 */
	class StepReturnHandler extends StepHandler {
		/**
		 * @see StepHandler#getStepKind()
		 */
		protected int getStepKind() {
			return StepRequest.STEP_OUT;
		}	
		
		/**
		 * @see StepHandler#getStepDetail()
		 */
		protected int getStepDetail() {
			return DebugEvent.STEP_RETURN;
		}		
	}
	
	/**
	 * Handler for stepping to a specific stack frame
	 * (stepping in the non-top stack frame). Step returns
	 * are performed until a specified stack frame is reached
	 * or the thread is suspended (explicitly, or by a
	 * breakpoint).
	 */
	class StepToFrameHandler extends StepReturnHandler {
		
		/**
		 * The number of frames that should be left on the stack
		 */
		private int fRemainingFrames;
		
		/**
		 * Constructs a step handler to step until the specified
		 * stack frame is reached.
		 * 
		 * @param frame the stack frame to step to
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected StepToFrameHandler(IStackFrame frame) throws DebugException {
			List frames = computeStackFrames();
			setRemainingFrames(frames.size() - frames.indexOf(frame));
		}
		
		/**
		 * Sets the number of frames that should be
		 * remaining on the stack when done.
		 * 
		 * @param num number of remaining frames
		 */
		protected void setRemainingFrames(int num) {
			fRemainingFrames = num;
		}
		
		/**
		 * Returns number of frames that should be
		 * remaining on the stack when done
		 * 
		 * @return number of frames that should be left
		 */
		protected int getRemainingFrames() {
			return fRemainingFrames;
		}
		
		/**
		 * Notification the step request has completed.
		 * If in the desired frame, complete the step
		 * request normally. If not in the desired frame,
		 * another step request is created and this thread
		 * is resumed.
		 * 
		 * @see IJDIEventListener#handleEvent(Event, JDIDebugTarget)
		 */
		public boolean handleEvent(Event event, JDIDebugTarget target) {
			try {
				int numFrames = getUnderlyingFrameCount();
				// tos should not be null
				if (numFrames <= getRemainingFrames()) {
					stepEnd();
					return false;
				} else {
					// reset running state and keep going
					setRunning(true);
					deleteStepRequest();
					createSecondaryStepRequest();
					return true;
				}
			} catch (DebugException e) {
				logError(e);
				stepEnd();
				return false;
			}
		}				
	}
	
	/**
	 * Handles dropping to a specified frame.
	 */
	class DropToFrameHandler extends StepReturnHandler {
		
		/**
		 * The number of frames to drop off the
		 * stack.
		 */
		private int fFramesToDrop;
		
		/**
		 * Constructs a handler to drop to the specified
		 * stack frame.
		 * 
		 * @param frame the stack frame to drop to
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */		
		protected DropToFrameHandler(IStackFrame frame) throws DebugException {
			List frames = computeStackFrames();
			setFramesToDrop(frames.indexOf(frame));
		}
		
		/**
		 * Sets the number of frames to pop off the stack.
		 * 
		 * @param num number of frames to pop
		 */
		protected void setFramesToDrop(int num) {
			fFramesToDrop = num;
		}
		
		/**
		 * Returns the number of frames to pop off the stack.
		 * 
		 * @return remaining number of frames to pop
		 */
		protected int getFramesToDrop() {
			return fFramesToDrop;
		}		
		
		/**
		 * To drop a frame or re-enter, the underlying thread is instructed
		 * to do a return. When the frame count is less than zero, the
		 * step being performed is a "step return", so a regular invocation
		 * is performed. 
		 * 
		 * @see StepHandler#invokeThread()
		 */
		protected void invokeThread() throws DebugException {
			if (getFramesToDrop() < 0) {
				super.invokeThread();
			} else {
				try {
					org.eclipse.jdi.hcr.ThreadReference hcrThread= (org.eclipse.jdi.hcr.ThreadReference) getUnderlyingThread();
					hcrThread.doReturn(null, true);
				} catch (RuntimeException e) {
					stepEnd();
					targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString(JDIDebugModelMessages.getString("JDIThread.exception_while_popping_stack_frame")), new String[] {e.toString()}), e); //$NON-NLS-1$
				}
			}
		}
		
		/**
		 * Notification that the pop has completed. If there are
		 * more frames to pop, keep going, otherwise re-enter the 
		 * top frame. Returns false, as this handler will resume this
		 * thread with a special invocation (<code>doReturn</code>).
		 * 
		 * @see IJDIEventListener#handleEvent(Event, JDIDebugTarget)
		 * @see #invokeThread()
		 */		
		public boolean handleEvent(Event event, JDIDebugTarget target) {
			// pop is complete, update number of frames to drop
			setFramesToDrop(getFramesToDrop() - 1);
			try {
				if (getFramesToDrop() >= -1) {
					deleteStepRequest();
					doSecondaryStep();
				} else {
					stepEnd();
				}
			} catch (DebugException e) {
				stepEnd();
				logError(e);
			}
			return false;
		}
		
		/**
		 * Pops a secondary frame off the stack, does a re-enter,
		 * or a step-into.
		 * 
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected void doSecondaryStep() throws DebugException {
			setStepRequest(createStepRequest());
			setPendingStepHandler(this);
			addJDIEventListener(this, getStepRequest());
			invokeThread();
		}		

		/**
		 * Creates and returns a step request. If there
		 * are no more frames to drop, a re-enter request
		 * is made. If the re-enter is complete, a step-into
		 * request is created.
		 * 
		 * @return step request
		 * @exception DebugException if this method fails.  Reasons include:
		 * <ul>
		 * <li>Failure communicating with the VM.  The DebugException's
		 * status code contains the underlying exception responsible for
		 * the failure.</li>
		 * </ul>
		 */
		protected StepRequest createStepRequest() throws DebugException {
			int num = getFramesToDrop();
			if (num > 0) {
				return super.createStepRequest();
			} else if (num == 0) {
				try {
					EventRequestManager erm= getEventRequestManager();
					StepRequest request = ((org.eclipse.jdi.hcr.EventRequestManager) erm).createReenterStepRequest(getUnderlyingThread());
					request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					request.addCountFilter(1);
					request.enable();
					return request;
				} catch (RuntimeException e) {
					targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_creating_step_request"), new String[] {e.toString()}), e); //$NON-NLS-1$
				}			
			} else if (num == -1) {
				try {
					StepRequest request = getEventRequestManager().createStepRequest(getUnderlyingThread(), StepRequest.STEP_LINE, StepRequest.STEP_INTO);
					request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
					request.addCountFilter(1);
					request.enable();
					return request;
				} catch (RuntimeException e) {
					targetRequestFailed(MessageFormat.format(JDIDebugModelMessages.getString("JDIThread.exception_creating_step_request"), new String[] {e.toString()}), e); //$NON-NLS-1$
				}					
			}
			// this line will never be executed, as the try block
			// will either return, or the catch block with throw 
			// an exception
			return null;
		}
	}
	

	/**
	 * @see IThread#hasStackFrames()
	 */
	public boolean hasStackFrames() throws DebugException {
		try {
			return computeStackFrames().size() > 0;
		} catch (DebugException e) {
			// do not throw an exception if the thread resumed while determining
			// whether stack frames are present
			if (e.getStatus().getCode() != IJavaThread.ERR_THREAD_NOT_SUSPENDED) {
				throw e;
			}
		}
		return false;
	}
		
	/**
	 * @see IAdaptable#getAdapter(Class)
	 */
	public Object getAdapter(Class adapter) {
		if (adapter == IJavaThread.class) {
			return this;
		}
		return super.getAdapter(adapter);
	}	
	
	/**
	 * @see org.eclipse.jdt.debug.core.IJavaThread#hasOwnedMonitors()
	 */
	public boolean hasOwnedMonitors() throws DebugException {
		return isSuspended() && getOwnedMonitors().length > 0;
	}
	
	
	/**
	 * @see org.eclipse.jdt.debug.core.IJavaThread#getOwnedMonitors()
	 */
	public IJavaObject[] getOwnedMonitors() throws DebugException {
		try {
			JDIDebugTarget target= (JDIDebugTarget)getDebugTarget();
			List ownedMonitors= getUnderlyingThread().ownedMonitors();
			IJavaObject[] javaOwnedMonitors= new IJavaObject[ownedMonitors.size()];
			Iterator itr= ownedMonitors.iterator();
			int i= 0;
			while (itr.hasNext()) {
				ObjectReference element = (ObjectReference) itr.next();
				javaOwnedMonitors[i]= new JDIObjectValue(target, element);
				i++;
			}
			return javaOwnedMonitors;
		} catch (IncompatibleThreadStateException e) {
		}
		return null;
	}

	/**
	 * @see org.eclipse.jdt.debug.core.IJavaThread#getContendedMonitor()
	 */
	public IJavaObject getContendedMonitor() throws DebugException {
		try {
			ObjectReference monitor= getUnderlyingThread().currentContendedMonitor();
			if (monitor != null) {
				return new JDIObjectValue((JDIDebugTarget)getDebugTarget(), monitor);
			}
		} catch (IncompatibleThreadStateException e) {
		}
		
		return null;
	}
	/**
	 * @see org.eclipse.debug.core.model.IFilteredStep#canStepWithFilters()
	 */
	public boolean canStepWithFilters() {
		return canStepInto() && getJavaDebugTarget().getStepFilters().length > 0;
	}

	/**
	 * @see org.eclipse.debug.core.model.IFilteredStep#stepWithFilters()
	 */
	public void stepWithFilters() throws DebugException {
		if (!canStepWithFilters()) {
			return;
		}
		fUseStepFilters = true;
		stepInto();
	}

}