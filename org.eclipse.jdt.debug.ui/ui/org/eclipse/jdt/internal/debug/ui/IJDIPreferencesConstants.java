package org.eclipse.jdt.internal.debug.ui;

/*
 * (c) Copyright IBM Corp. 2000, 2001, 2002.
 * All Rights Reserved.
 */
import org.eclipse.jdt.debug.ui.IJavaDebugUIConstants;
 
/**
 * Defines constants which are used to refer to values in the plugin's preference store.
 */
public interface IJDIPreferencesConstants {
	
	/**
	 * Boolean preference controlling whether primitive types
	 * types display hexidecimal values.
	 */	
	public static final String PREF_SHOW_HEX_VALUES= IJavaDebugUIConstants.PLUGIN_ID + "javaDebug.showHexValues"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether primitive types
	 * types display char values.
	 */	
	public static final String PREF_SHOW_CHAR_VALUES= IJavaDebugUIConstants.PLUGIN_ID + "javaDebug.showCharValues"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether primitive types
	 * types display unsigned values.
	 */	
	public static final String PREF_SHOW_UNSIGNED_VALUES= IJavaDebugUIConstants.PLUGIN_ID + "javaDebug.showUnsignedValues"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether to suspend
	 * execution when an uncaught Java exceptionis encountered
	 * (while debugging).
	 */	
	public static final String PREF_SUSPEND_ON_UNCAUGHT_EXCEPTIONS= IJavaDebugUIConstants.PLUGIN_ID + "javaDebug.SuspendOnUncaughtExceptions"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether to suspend
	 * execution when a compilation error is encountered
	 * (while debugging).
	 */
	public static final String PREF_SUSPEND_ON_COMPILATION_ERRORS= IJavaDebugUIConstants.PLUGIN_ID + ".suspend_on_compilation_errors"; //$NON-NLS-1$
		
	/**
	 * Boolean preference controlling whether synthetic
	 * methods are to be filtered when stepping (and step
	 * filters are enabled).
	 */	
	public static final String PREF_FILTER_SYNTHETICS = IJavaDebugUIConstants.PLUGIN_ID + ".filter_synthetics"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether static
	 * initializers are to be filtered when stepping (and step
	 * filters are enabled).
	 */		
	public static final String PREF_FILTER_STATIC_INITIALIZERS = IJavaDebugUIConstants.PLUGIN_ID + ".filter_statics"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether constructors
	 * are to be filtered when stepping (and step
	 * filters are enabled).
	 */			
	public static final String PREF_FILTER_CONSTRUCTORS = IJavaDebugUIConstants.PLUGIN_ID + ".filter_constructors"; //$NON-NLS-1$
	
	/**
	 * List of active step filters. A String containing a comma
	 * separated list of fully qualified type names/patterns.
	 */			
	public static final String PREF_ACTIVE_FILTERS_LIST = IJavaDebugUIConstants.PLUGIN_ID + ".active_filters"; //$NON-NLS-1$
	
	/**
	 * List of inactive step filters. A String containing a comma
	 * separated list of fully qualified type names/patterns.
	 */				
	public static final String PREF_INACTIVE_FILTERS_LIST = IJavaDebugUIConstants.PLUGIN_ID + ".inactive_filters"; //$NON-NLS-1$	
	
	/**
	 * Boolean preference controlling whether to alert
	 * with a dialog when hot code replace fails.
	 */	
	public static final String PREF_ALERT_HCR_FAILED = IJavaDebugUIConstants.PLUGIN_ID + ".javaDebug.alertHCRFailed"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether to alert
	 * with a dialog when hot code replace is not supported.
	 */	
	public static final String PREF_ALERT_HCR_NOT_SUPPORTED = IJavaDebugUIConstants.PLUGIN_ID + ".javaDebug.alertHCRNotSupported"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether to alert
	 * with a dialog when hot code replace results in 
	 * obsolete methods.
	 */	
	public static final String PREF_ALERT_OBSOLETE_METHODS = IJavaDebugUIConstants.PLUGIN_ID + "javaDebug.alertObsoleteMethods"; //$NON-NLS-1$
	
	/**
	 * Boolean preference controlling whether the debugger shows 
	 * qualifed names. When <code>true</code> the debugger
	 * will show qualified names in newly opened views.
	 * 
	 * @since 2.0
	 */
	public static final String PREF_SHOW_QUALIFIED_NAMES= IJavaDebugUIConstants.PLUGIN_ID + ".show_qualified_names"; //$NON-NLS-1$
	
	/**
	 * List of defined detail formatters.A String containing a comma
	 * separated list of fully qualified type names, the associated
	 * code snippet and an 'enabled' flag.
	 */
	public static final String PREF_DETAIL_FORMATTERS_LIST= IJavaDebugUIConstants.PLUGIN_ID + ".detail_formatters"; //$NON-NLS-1$
	
}