/*******************************************************************************
 * Copyright 2011 See LIBGDX_AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.badlogic.gdx.backends.iosrobovm;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.iosrobovm.objectal.OALAudioSession;
import com.badlogic.gdx.backends.iosrobovm.objectal.OALSimpleAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import org.mini2Dx.core.game.GameContainer;
import org.mini2Dx.ios.IOSGameWrapper;
import org.mini2Dx.libgdx.game.ApplicationListener;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.*;
import org.robovm.apple.uikit.*;
import org.robovm.rt.bro.Bro;

import java.io.File;

/**
 * Launches iOS-based mini2Dx games. Based on <a href=
 * "https://github.com/libgdx/libgdx/blob/master/backends/gdx-backend-robovm/src/com/badlogic/gdx/backends/iosrobovm/IOSApplication.java">
 * LibGDX's IOSApplication class</a>
 */
public class IOSMini2DxGame implements Application {

	public static abstract class Delegate extends UIApplicationDelegateAdapter {
		private IOSMini2DxGame app;

		protected abstract IOSMini2DxGame createApplication ();

		@Override
		public boolean didFinishLaunching (UIApplication application, UIApplicationLaunchOptions launchOptions) {
			application.addStrongRef(this); // Prevent this from being GCed until the ObjC UIApplication is deallocated
			this.app = createApplication();
			return app.didFinishLaunching(application, launchOptions);
		}

		@Override
		public void didBecomeActive (UIApplication application) {
			app.didBecomeActive(application);
		}

		@Override
		public void willEnterForeground (UIApplication application) {
			app.willEnterForeground(application);
		}

		@Override
		public void willResignActive (UIApplication application) {
			app.willResignActive(application);
		}

		@Override
		public void willTerminate (UIApplication application) {
			app.willTerminate(application);
		}
	}

	UIApplication uiApp;
	UIWindow uiWindow;
	ApplicationListener listener;
	IOSViewControllerListener viewControllerListener;
	IOSMini2DxConfig config;
	IOSMini2DxGraphics graphics;
	IOSMini2DxAudio audio;
	IOSFiles files;
	IOSMini2DxInput input;
	IOSMini2DxNet net;
	int logLevel = Application.LOG_DEBUG;
	ApplicationLogger applicationLogger;

	/** The display scale factor (1.0f for normal; 2.0f to use retina coordinates/dimensions). */
	float pixelsPerPoint;
	
	private IOSScreenBounds lastScreenBounds = null;

	Array<Runnable> runnables = new Array<Runnable>();
	Array<Runnable> executedRunnables = new Array<Runnable>();
	Array<LifecycleListener> lifecycleListeners = new Array<LifecycleListener>();

	public IOSMini2DxGame (GameContainer game, IOSMini2DxConfig config) {
		this.listener = new IOSGameWrapper(game, config.gameIdentifier);
		this.config = config;
	}

	final boolean didFinishLaunching (UIApplication uiApp, UIApplicationLaunchOptions options) {
		setApplicationLogger(new IOSApplicationLogger());
		Gdx.app = this;
		this.uiApp = uiApp;

		// enable or disable screen dimming
		uiApp.setIdleTimerDisabled(config.preventScreenDimming);

		Gdx.app.debug("IOSApplication", "iOS version: " + UIDevice.getCurrentDevice().getSystemVersion());
		Gdx.app.debug("IOSApplication", "Running in " + (Bro.IS_64BIT ? "64-bit" : "32-bit") + " mode");

		// iOS counts in "points" instead of pixels. Points are logical pixels
		pixelsPerPoint = (float)UIScreen.getMainScreen().getNativeScale();
		Gdx.app.debug("IOSApplication", "Pixels per point: " + pixelsPerPoint);

		// setup libgdx
		this.input = new IOSMini2DxInput(this);
		this.graphics = new IOSMini2DxGraphics(this, config, input, config.useGL30);
		Gdx.gl = Gdx.gl20 = graphics.gl20;
		Gdx.gl30 = graphics.gl30;
		this.files = new IOSFiles();
		this.audio = new IOSMini2DxAudio(config);
		this.net = new IOSMini2DxNet(this);

		Gdx.files = this.files;
		Gdx.graphics = this.graphics;
		Gdx.audio = this.audio;
		Gdx.input = this.input;
		Gdx.net = this.net;

		this.input.setupPeripherals();

		this.uiWindow = new UIWindow(UIScreen.getMainScreen().getBounds());
		this.uiWindow.setRootViewController(this.graphics.viewController);
		this.uiWindow.makeKeyAndVisible();
		Gdx.app.debug("IOSMini2DxGame", "created");
		return true;
	}

	protected IOSMini2DxGraphics.IOSUIViewController createUIViewController (IOSMini2DxGraphics graphics) {
		return new IOSMini2DxGraphics.IOSUIViewController(this, graphics);
	}

	/** Returns device ppi using a best guess approach when device is unknown. Overwrite to customize strategy. */
	protected int guessUnknownPpi() {
		int ppi;
		if (UIDevice.getCurrentDevice().getUserInterfaceIdiom() == UIUserInterfaceIdiom.Pad)
			ppi = 132 * (int) pixelsPerPoint;
		else
			ppi = 164 * (int) pixelsPerPoint;
		error("IOSApplication", "Device PPI unknown. PPI value has been guessed to " + ppi + " but may be wrong");
		return ppi;
	}

	private int getIosVersion () {
		String systemVersion = UIDevice.getCurrentDevice().getSystemVersion();
		int version = Integer.parseInt(systemVersion.split("\\.")[0]);
		return version;
	}

	/** Return the UI view controller of IOSApplication
	 * @return the view controller of IOSApplication */
	public UIViewController getUIViewController () {
		return graphics.viewController;
	}

	/** Return the UI Window of IOSApplication
	 * @return the window */
	public UIWindow getUIWindow () {
		return uiWindow;
	}

	/** @see IOSScreenBounds for detailed explanation
	 * @return logical dimensions of space we draw to, adjusted for device orientation */
	protected IOSScreenBounds computeBounds () {
		CGRect screenBounds = uiWindow.getBounds();
		final CGRect statusBarFrame = uiApp.getStatusBarFrame();
		double statusBarHeight = statusBarFrame.getHeight();

		double screenWidth = screenBounds.getWidth();
		double screenHeight = screenBounds.getHeight();

		if (statusBarHeight != 0.0) {
			debug("IOSApplication", "Status bar is visible (height = " + statusBarHeight + ")");
			screenHeight -= statusBarHeight;
		} else {
			debug("IOSApplication", "Status bar is not visible");
		}
		final int offsetX = 0;
		final int offsetY = (int)Math.round(statusBarHeight);

		final int width = (int)Math.round(screenWidth);
		final int height = (int)Math.round(screenHeight);

		final int backBufferWidth = (int)Math.round(screenWidth * pixelsPerPoint);
		final int backBufferHeight = (int)Math.round(screenHeight * pixelsPerPoint);

		debug("IOSApplication", "Computed bounds are x=" + offsetX + " y=" + offsetY + " w=" + width + " h=" + height + " bbW= "
				+ backBufferWidth + " bbH= " + backBufferHeight);

		return lastScreenBounds = new IOSScreenBounds(offsetX, offsetY, width, height, backBufferWidth, backBufferHeight);
	}

	/** @return area of screen in UIKit points on which libGDX draws, with 0,0 being upper left corner */
	public IOSScreenBounds getScreenBounds () {
		if (lastScreenBounds == null)
			return computeBounds();
		else
			return lastScreenBounds;
	}

	final void didBecomeActive (UIApplication uiApp) {
		Gdx.app.debug("IOSApplication", "resumed");
		// workaround for ObjectAL crash problem
		// see: https://groups.google.com/forum/?fromgroups=#!topic/objectal-for-iphone/ubRWltp_i1Q
		OALAudioSession audioSession = OALAudioSession.sharedInstance();
		if (audioSession != null) {
			audioSession.forceEndInterruption();
		}
		if (config.allowIpod) {
			OALSimpleAudio audio = OALSimpleAudio.sharedInstance();
			if (audio != null) {
				audio.setUseHardwareIfAvailable(false);
			}
		}
		graphics.makeCurrent();
		graphics.resume();
	}

	final void willEnterForeground (UIApplication uiApp) {
		// workaround for ObjectAL crash problem
		// see: https://groups.google.com/forum/?fromgroups=#!topic/objectal-for-iphone/ubRWltp_i1Q
		OALAudioSession audioSession = OALAudioSession.sharedInstance();
		if (audioSession != null) {
			audioSession.forceEndInterruption();
		}
	}

	final void willResignActive (UIApplication uiApp) {
		Gdx.app.debug("IOSApplication", "paused");
		graphics.makeCurrent();
		graphics.pause();
		Gdx.gl.glFlush();
	}

	final void willTerminate (UIApplication uiApp) {
		Gdx.app.debug("IOSApplication", "disposed");
		graphics.makeCurrent();
		Array<LifecycleListener> listeners = lifecycleListeners;
		synchronized (listeners) {
			for (LifecycleListener listener : listeners) {
				listener.pause();
			}
		}
		listener.dispose();
		Gdx.gl.glFlush();
	}

	@Override
	public ApplicationListener getApplicationListener () {
		return listener;
	}

	@Override
	public Graphics getGraphics () {
		return graphics;
	}

	@Override
	public Audio getAudio () {
		return audio;
	}

	@Override
	public Input getInput () {
		return input;
	}

	@Override
	public Files getFiles () {
		return files;
	}

	@Override
	public Net getNet () {
		return net;
	}

	@Override
	public void log (String tag, String message) {
		if (logLevel > LOG_NONE) {
			Foundation.log("%@", new NSString("[info] " + tag + ": " + message));
		}
	}

	@Override
	public void log (String tag, String message, Throwable exception) {
		if (logLevel > LOG_NONE) {
			Foundation.log("%@", new NSString("[info] " + tag + ": " + message));
			exception.printStackTrace();
		}
	}

	@Override
	public void error (String tag, String message) {
		if (logLevel >= LOG_ERROR) {
			Foundation.log("%@", new NSString("[error] " + tag + ": " + message));
		}
	}

	@Override
	public void error (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_ERROR) {
			Foundation.log("%@", new NSString("[error] " + tag + ": " + message));
			exception.printStackTrace();
		}
	}

	@Override
	public void debug (String tag, String message) {
		if (logLevel >= LOG_DEBUG) {
			Foundation.log("%@", new NSString("[debug] " + tag + ": " + message));
		}
	}

	@Override
	public void debug (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_DEBUG) {
			Foundation.log("%@", new NSString("[debug] " + tag + ": " + message));
			exception.printStackTrace();
		}
	}

	@Override
	public void setLogLevel (int logLevel) {
		this.logLevel = logLevel;
	}

	@Override
	public int getLogLevel () {
		return logLevel;
	}
	
	@Override
	public void setApplicationLogger (ApplicationLogger applicationLogger) {
		this.applicationLogger = applicationLogger;
	}

	@Override
	public ApplicationLogger getApplicationLogger () {
		return applicationLogger;
	}

	@Override
	public ApplicationType getType () {
		return ApplicationType.iOS;
	}

	@Override
	public int getVersion () {
		return Integer.parseInt(UIDevice.getCurrentDevice().getSystemVersion().split("\\.")[0]);
	}

	@Override
	public long getJavaHeap () {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	@Override
	public long getNativeHeap () {
		return getJavaHeap();
	}

	@Override
	public Preferences getPreferences (String name) {
		File libraryPath = new File(System.getenv("HOME"), "Library");
		File finalPath = new File(libraryPath, name + ".plist");

		@SuppressWarnings("unchecked")
		NSMutableDictionary<NSString, NSObject> nsDictionary = (NSMutableDictionary<NSString, NSObject>)NSMutableDictionary
			.read(finalPath);

		// if it fails to get an existing dictionary, create a new one.
		if (nsDictionary == null) {
			nsDictionary = new NSMutableDictionary<NSString, NSObject>();
			nsDictionary.write(finalPath, false);
		}
		return new IOSPreferences(nsDictionary, finalPath.getAbsolutePath());
	}

	@Override
	public void postRunnable (Runnable runnable) {
		synchronized (runnables) {
			runnables.add(runnable);
			Gdx.graphics.requestRendering();
		}
	}

	public void processRunnables () {
		synchronized (runnables) {
			executedRunnables.clear();
			executedRunnables.addAll(runnables);
			runnables.clear();
		}
		for (int i = 0; i < executedRunnables.size; i++) {
			try {
				executedRunnables.get(i).run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	@Override
	public void exit () {
		NSThread.exit();
	}

	@Override
	public Clipboard getClipboard () {
		return new Clipboard() {
			@Override
			public void setContents (String content) {
				UIPasteboard.getGeneralPasteboard().setString(content);
			}

			@Override
			public boolean hasContents () {
				if (Foundation.getMajorSystemVersion() >= 10) {
					return UIPasteboard.getGeneralPasteboard().hasStrings();
				}

				String contents = getContents();
				return contents != null && !contents.isEmpty();
			}

			@Override
			public String getContents () {
				return UIPasteboard.getGeneralPasteboard().getString();
			}
		};
	}

	@Override
	public void addLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.add(listener);
		}
	}

	@Override
	public void removeLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.removeValue(listener, true);
		}
	}

	/** Add a listener to handle events from the libgdx root view controller
	 * @param listener The {#link IOSViewControllerListener} to add */
	public void addViewControllerListener (IOSViewControllerListener listener) {
		viewControllerListener = listener;
	}
}
