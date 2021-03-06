package com.xqbase.tuna.gui;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import com.xqbase.tuna.ConnectorImpl;

public abstract class ConnectorFrame extends JFrame {
	private static final long serialVersionUID = 1L;

	protected abstract void start();
	protected void windowClosed() {/**/}

	protected TrayIcon trayIcon;
	protected final MenuItem startMenuItem = new MenuItem("Start");
	protected final JButton startButton = new JButton("Start");
	protected final JButton exitButton = new JButton("Exit");
	protected ConnectorImpl connector = null;

	private Insets insets = new Insets(0, 0, 0, 0);
	private KeyAdapter keyAdapter = new KeyAdapter() {
		@Override
		public void keyPressed(KeyEvent e) {
			if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
				dispose();
			}
		}
	};

	@Override
	public Component add(Component comp) {
		if (comp instanceof JButton) {
			((JButton) comp).setMargin(insets);
		}
		if (comp.isFocusable()) {
			comp.addKeyListener(keyAdapter);
		}
		return super.add(comp);
	}

	protected ConnectorFrame(String title, String icon,
			final int width, final int height, final boolean tray) {
		super(title);
		setLayout(null);
		setLocationByPlatform(true);
		setResizable(false);

		startButton.addActionListener(e -> start());
		add(startButton);
		exitButton.addActionListener(e -> dispose());
		add(exitButton);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				Insets i = getInsets();
				setSize(width + i.left + i.right, height + i.top + i.bottom);
			}

			@Override
			public void windowIconified(WindowEvent e) {
				if (tray && SystemTray.isSupported()) {
					setVisible(false);
					try {
						SystemTray.getSystemTray().add(trayIcon);
					} catch (AWTException ex) {
						throw new RuntimeException(ex);
					}
				}
			}

			@Override
			public void windowClosing(WindowEvent e) {
				dispose();
			}

			@Override
			public void windowClosed(WindowEvent e) {
				running = false;
				ConnectorFrame.this.windowClosed();
			}
		});

		Class<ConnectorFrame> clazz = ConnectorFrame.class;
		try (InputStream in16 = clazz.getResourceAsStream(icon + "Icon16.gif");
				InputStream in32 = clazz.getResourceAsStream(icon + "Icon32.gif");
				InputStream in48 = clazz.getResourceAsStream(icon + "Icon48.gif")) {
			setIconImages(Arrays.asList(new Image[] {ImageIO.read(in16),
					ImageIO.read(in32), ImageIO.read(in48)}));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (!tray) {
			return;
		}
		MenuItem miOpen = new MenuItem("Open"), miExit = new MenuItem("Exit");
		Font font = startButton.getFont();
		miOpen.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
		startMenuItem.setFont(font);
		miExit.setFont(font);
		PopupMenu popup = new PopupMenu();
		popup.add(miOpen);
		popup.add(startMenuItem);
		popup.add(miExit);
		try (InputStream in = clazz.getResourceAsStream(icon + "Icon16.gif")) {
			trayIcon = new TrayIcon(ImageIO.read(in), getTitle(), popup);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		trayIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					SystemTray.getSystemTray().remove(trayIcon);
					setVisible(true);
					setState(NORMAL);
				}
			}
		});

		miOpen.addActionListener(e -> {
			SystemTray.getSystemTray().remove(trayIcon);
			setVisible(true);
			setState(NORMAL);
		});
		startMenuItem.addActionListener(e -> start());
		miExit.addActionListener(e -> {
			SystemTray.getSystemTray().remove(trayIcon);
			dispose();
		});
	}

	protected void onClose() {/**/}

	boolean running = false;

	@Override
	public void setVisible(boolean b) {
		super.setVisible(b);
		if (!b || running) {
			return;
		}
		running = true;
		// Unable to change to lambda here
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				if (connector == null) {
					try {
						Thread.sleep(16);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				} else {
					connector.doEvents(16);
				}
				if (running) {
					EventQueue.invokeLater(this);
				} else {
					onClose();
					if (connector != null) {
						connector.close();
					}
				}
			}
		});
	}

	protected static void invoke(Class<? extends ConnectorFrame> frameClass) {
		final ConnectorFrame frame;
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			frame = frameClass.newInstance();
		} catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
			throw new RuntimeException(e);
		}
		UIManager.put("AuditoryCues.playList",
				UIManager.get("AuditoryCues.allAuditoryCues"));
		EventQueue.invokeLater(() -> frame.setVisible(true));
	}
}