/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.launching;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaProject;

/**
 * Default implementation for classpath provider.
 * <p>
 * This class may be subclassed.
 * </p>
 * @since 2.0
 */
public class StandardClasspathProvider implements IRuntimeClasspathProvider {

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.launching.IRuntimeClasspathProvider#computeUnresolvedClasspath(org.eclipse.debug.core.ILaunchConfiguration)
	 */
	public IRuntimeClasspathEntry[] computeUnresolvedClasspath(ILaunchConfiguration configuration) throws CoreException {
		boolean useDefault = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_DEFAULT_CLASSPATH, true);
		if (useDefault) {
			IJavaProject proj = JavaRuntime.getJavaProject(configuration);
			if (proj == null) {
				//no project - use JRE's default libraries
				return computeJRELibraries(configuration);				
			}
			return JavaRuntime.computeUnresolvedRuntimeClasspath(proj);
		}
		// recover persisted classpath
		return recoverRuntimePath(configuration, IJavaLaunchConfigurationConstants.ATTR_CLASSPATH);
	}

	private IRuntimeClasspathEntry[] computeJRELibraries(ILaunchConfiguration configuration) throws CoreException {
		IVMInstall vm = JavaRuntime.computeVMInstall(configuration);
		IPath path = new Path(JavaRuntime.JRE_CONTAINER);
		path = path.append(vm.getVMInstallType().getId()).append(vm.getName());
		IRuntimeClasspathEntry entry = JavaRuntime.newRuntimeContainerClasspathEntry(path, IRuntimeClasspathEntry.STANDARD_CLASSES);
		return new IRuntimeClasspathEntry[]{entry};
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.launching.IRuntimeClasspathProvider#resolveClasspath(org.eclipse.jdt.launching.IRuntimeClasspathEntry[], org.eclipse.debug.core.ILaunchConfiguration)
	 */
	public IRuntimeClasspathEntry[] resolveClasspath(IRuntimeClasspathEntry[] entries, ILaunchConfiguration configuration) throws CoreException {
		List all = new ArrayList(entries.length);
		for (int i = 0; i < entries.length; i++) {
			IRuntimeClasspathEntry[] resolved =JavaRuntime.resolveRuntimeClasspathEntry(entries[i], configuration);
			for (int j = 0; j < resolved.length; j++) {
				all.add(resolved[j]);
			}
		}
		return (IRuntimeClasspathEntry[])all.toArray(new IRuntimeClasspathEntry[all.size()]);
	}
	
	/**
	 * Returns a collection of runtime classpath entries that are defined in the
	 * specified attribute of the given launch configuration. When present,
	 * the attribute must contain a list of runtime classpath entry mementos.
	 * 
	 * @param configuration launch configuration
	 * @param attribute attribute name containing the list of entries
	 * @return collection of runtime classpath entries that are defined in the
	 *  specified attribute of the given launch configuration
	 * @exception CoreException if unable to retrieve the list
	 */
	protected IRuntimeClasspathEntry[] recoverRuntimePath(ILaunchConfiguration configuration, String attribute) throws CoreException {
		List entries = configuration.getAttribute(attribute, Collections.EMPTY_LIST);
		IRuntimeClasspathEntry[] rtes = new IRuntimeClasspathEntry[entries.size()];
		Iterator iter = entries.iterator();
		int i = 0;
		while (iter.hasNext()) {
			rtes[i] = JavaRuntime.newRuntimeClasspathEntry((String)iter.next());
			i++;
		}
		return rtes;		
	}	

}
