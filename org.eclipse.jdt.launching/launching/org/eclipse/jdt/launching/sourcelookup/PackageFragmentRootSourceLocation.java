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
package org.eclipse.jdt.launching.sourcelookup;


import java.io.IOException;
import java.io.StringReader;
import java.text.MessageFormat;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.launching.JavaLaunchConfigurationUtils;
import org.eclipse.jdt.internal.launching.LaunchingMessages;
import org.eclipse.jdt.internal.launching.LaunchingPlugin;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Locates source elements in a package fragment root. Returns
 * instances of <code>ICompilationUnit</code> and
 * </code>IClassFile</code>.
 * <p>
 * This class may be instantiated; it is not intended to be subclassed.
 * </p>
 * @see IJavaSourceLocation
 * @since 2.1
 * @deprecated In 3.0, the debug platform provides source lookup facilities that
 *  should be used in place of the Java source lookup support provided in 2.0.
 *  The new facilities provide a source lookup director that coordinates source
 *  lookup among a set of participants, searching a set of source containers.
 *  See the following packages: <code>org.eclipse.debug.core.sourcelookup</code>
 *  and <code>org.eclipse.debug.core.sourcelookup.containers</code>. This class
 *  has been replaced by
 *  <code>org.eclipse.jdt.launching.sourcelookup.containers.PackageFragmentRootSourceContainer</code>.
 */
public class PackageFragmentRootSourceLocation extends PlatformObject implements IJavaSourceLocation {
	
	/**
	 * Associatd package framgment root 
	 */
	private IPackageFragmentRoot fRoot = null;
	
	/**
	 * Creates an empty source location.
	 */
	public PackageFragmentRootSourceLocation() {
	}	
	
	/**
	 * Creates a source location on the given package fragment root.
	 * 
	 * @param root package fragment root
	 */
	public PackageFragmentRootSourceLocation(IPackageFragmentRoot root) {
		setPackageFragmentRoot(root);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.launching.sourcelookup.IJavaSourceLocation#findSourceElement(java.lang.String)
	 */
	public Object findSourceElement(String name) throws CoreException {
		if (name != null && getPackageFragmentRoot() != null) {
			IPackageFragment pkg = null;
			int index = name.lastIndexOf('.');
			if (index >= 0) {
				String fragment = name.substring(0, index);
				pkg = getPackageFragmentRoot().getPackageFragment(fragment);
				name = name.substring(index + 1);
			} else {
				pkg = getPackageFragmentRoot().getPackageFragment(""); //$NON-NLS-1$
			}
			if (pkg.exists()) {
				boolean possibleInnerType = false;
				String typeName = name;
				do {
					ICompilationUnit cu = pkg.getCompilationUnit(typeName + ".java"); //$NON-NLS-1$
					if (cu.exists()) {
						return cu;
					} 
					IClassFile cf = pkg.getClassFile(typeName + ".class"); //$NON-NLS-1$
					if (cf.exists()) {
						return cf;
					}				
					index = typeName.lastIndexOf('$');
					if (index >= 0) {
						typeName = typeName.substring(0, index);
						possibleInnerType = true;
					} else {
						possibleInnerType = false;
					}						
				} while (possibleInnerType);
			}
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.launching.sourcelookup.IJavaSourceLocation#getMemento()
	 */
	public String getMemento() throws CoreException {
		try {
			Document doc = LaunchingPlugin.getDocument();
			Element node = doc.createElement("javaPackageFragmentRootSourceLocation"); //$NON-NLS-1$
			doc.appendChild(node);
			String handle = ""; //$NON-NLS-1$
			if (getPackageFragmentRoot() != null) {
				handle = getPackageFragmentRoot().getHandleIdentifier();
			}
			node.setAttribute("handleId", handle); //$NON-NLS-1$
			return JavaLaunchConfigurationUtils.serializeDocument(doc);
		} catch (IOException e) {
			abort(MessageFormat.format(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Unable_to_create_memento_for_package_fragment_root_source_location_{0}_5"), new String[] {getPackageFragmentRoot().getElementName()}), e); //$NON-NLS-1$
		} catch (ParserConfigurationException e) {
			abort(MessageFormat.format(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Unable_to_create_memento_for_package_fragment_root_source_location_{0}_5"), new String[] {getPackageFragmentRoot().getElementName()}), e); //$NON-NLS-1$
		} catch (TransformerException e) {
			abort(MessageFormat.format(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Unable_to_create_memento_for_package_fragment_root_source_location_{0}_5"), new String[] {getPackageFragmentRoot().getElementName()}), e); //$NON-NLS-1$
		}
		// execution will not reach here
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.launching.sourcelookup.IJavaSourceLocation#initializeFrom(java.lang.String)
	 */
	public void initializeFrom(String memento) throws CoreException {
		Exception ex = null;
		try {
			Element root = null;
			DocumentBuilder parser =
				DocumentBuilderFactory.newInstance().newDocumentBuilder();
			StringReader reader = new StringReader(memento);
			InputSource source = new InputSource(reader);
			root = parser.parse(source).getDocumentElement();
												
			String handle = root.getAttribute("handleId"); //$NON-NLS-1$
			if (handle == null) {
				abort(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Unable_to_initialize_source_location_-_missing_handle_identifier_for_package_fragment_root._6"), null); //$NON-NLS-1$
			} else {
				if (handle.length() == 0) {
					// empty package fragment
					setPackageFragmentRoot(null);
				} else {
					IJavaElement element = JavaCore.create(handle);
					if (element instanceof IPackageFragmentRoot) {
						setPackageFragmentRoot((IPackageFragmentRoot)element);
					} else {
						abort(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Unable_to_initialize_source_location_-_package_fragment_root_does_not_exist._7"), null); //$NON-NLS-1$
					}
				}
			}
			return;
		} catch (ParserConfigurationException e) {
			ex = e;			
		} catch (SAXException e) {
			ex = e;
		} catch (IOException e) {
			ex = e;
		}
		abort(LaunchingMessages.getString("PackageFragmentRootSourceLocation.Exception_occurred_initializing_source_location._8"), ex); //$NON-NLS-1$
	}

	/**
	 * Returns the package fragment root associated with this
	 * source location, or <code>null</code> if none
	 * 
	 * @return the package fragment root associated with this
	 *  source location, or <code>null</code> if none
	 */
	public IPackageFragmentRoot getPackageFragmentRoot() {
		return fRoot;
	}

	/**
	 * Sets the package fragment root associated with this
	 * source location.
	 * 
	 * @param root package fragment root
	 */
	private void setPackageFragmentRoot(IPackageFragmentRoot root) {
		fRoot = root;
	}
	
	/*
	 * Throws an internal error exception
	 */
	private void abort(String message, Throwable e)	throws CoreException {
		IStatus s = new Status(IStatus.ERROR, LaunchingPlugin.getUniqueIdentifier(), IJavaLaunchConfigurationConstants.ERR_INTERNAL_ERROR, message, e);
		throw new CoreException(s);		
	}	
	
	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object object) {		
		if (object instanceof PackageFragmentRootSourceLocation) {
			 PackageFragmentRootSourceLocation root = (PackageFragmentRootSourceLocation)object;
			 if (getPackageFragmentRoot() == null) {
			 	return root.getPackageFragmentRoot() == null;
			 } 
			 return getPackageFragmentRoot().equals(root.getPackageFragmentRoot());
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	public int hashCode() {
		if (getPackageFragmentRoot() == null) {
			return getClass().hashCode();
		} 
		return getPackageFragmentRoot().hashCode();
	}	
}