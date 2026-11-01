package com.seibel.distanthorizons.common.wrappers.gui;

#if MC_VER <= MC_1_12_2
import javax.swing.JOptionPane;
#elif MC_VER <= MC_26_2_0
import org.lwjgl.util.tinyfd.TinyFileDialogs;
#else
import javax.swing.*;
#endif

/**
 * Should be used instead of the direct call to TinyFileDialogs
 * so we can run additional validation, string cleanup,
 * and use different backends for MC versions that
 * don't support TinyFileDialogs. <br><br>
 * 
 * source:
 * https://sourceforge.net/projects/tinyfiledialogs/
 */
public class NativeDialogUtil
{
	// TODO these string options should be replaced with an enum
	/**
	 * @param dialogType    the dialog type. One of:<br><table><tr><td>"ok"</td><td>"okcancel"</td><td>"yesno"</td><td>"yesnocancel"</td></tr></table>
	 * @param iconType      the icon type. One of:<br><table><tr><td>"info"</td><td>"warning"</td><td>"error"</td><td>"question"</td></tr></table>
	 */
	public static void showDialog(String title, String message, String dialogType, String iconType)
	{
		// Tinyfd doesn't support the following characters, attempting to display them will cause the message
		// to be replaced with an error message
		String unsafeCharsRegex = "['\"`]";
		
		title = title.replaceAll(unsafeCharsRegex, "");
		message = message.replaceAll(unsafeCharsRegex, "");
		
		#if MC_VER <= MC_1_12_2
		jSwingDialog(title, message, iconType);
		#elif MC_VER <= MC_1_21_11
		TinyFileDialogs.tinyfd_messageBox(title, message, dialogType, iconType, false);
		#elif MC_VER <= MC_26_2_0
		// https://mfbridge.github.io/tinyfiledialogs/reference/messageBox.html
		TinyFileDialogs.tinyfd_messageBox(title, message, dialogType, iconType, 1 /* ok/yes */);
		#else
		// Java swing is being used because MC removed TinyFileDialogs in MC 26.3.0.
		// This is known not to work on Mac, so if that becomes an issue in the future
		// we may want to look into embedding our own copy of TinyFileDialogs.
		jSwingDialog(title, message, iconType);
		#endif
	}

	#if MC_VER <= MC_1_12_2 || MC_VER >= MC_26_3_0
	private static void jSwingDialog(String title, String message, String iconType)
	{
		int messageType;
		switch (iconType)
		{
			case "error":
				messageType = JOptionPane.ERROR_MESSAGE;
				break;
			case "warning":
				messageType = JOptionPane.WARNING_MESSAGE;
				break;
			case "info":
				messageType = JOptionPane.INFORMATION_MESSAGE;
				break;
			case "question":
				messageType = JOptionPane.QUESTION_MESSAGE;
				break;
			default:
				messageType = JOptionPane.PLAIN_MESSAGE;
				break;
		}
		JOptionPane.showConfirmDialog(null, message, title, JOptionPane.DEFAULT_OPTION, messageType);
	}
	#endif
	
	
	/** 
	 * if we're using Java Swing for our UI
	 * dialogs then AWT needs to be set to "false"
	 * otherwise the AWT setting doesn't matter.
	 */
	public static boolean needsAwtHeadless()
	{
		#if MC_VER <= MC_1_12_2
		return false;
		#else
		return true;
		#endif
	}
	
	
	
}
