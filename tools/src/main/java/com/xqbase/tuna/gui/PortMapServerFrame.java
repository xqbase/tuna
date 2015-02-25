package com.xqbase.tuna.gui;

import java.io.IOException;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.portmap.PortMapServer;
import com.xqbase.util.Numbers;

public class PortMapServerFrame extends ConnectorFrame {
	private static final long serialVersionUID = 1L;

	private JTextField txtMapPort = new JTextField("8341");

	private PortMapServer server;

	void stop() {
		trayIcon.setToolTip(getTitle());
		startMenuItem.setLabel("Start");
		startButton.setText("Start");
		txtMapPort.setEnabled(true);
	}

	@Override
	protected void start() {
		if (connector != null) {
			server.close();
			connector.close();
			connector = null;
			stop();
			return;
		}
		trayIcon.setToolTip(getTitle() + " (" + txtMapPort.getText() + ")");
		startMenuItem.setLabel("Stop");
		startButton.setText("Stop");
		txtMapPort.setEnabled(false);

		connector = new ConnectorImpl();
		server = new PortMapServer(connector, connector);
		try {
			connector.add(server, Numbers.parseInt(txtMapPort.getText(), 1, 65535));
		} catch (IOException e) {
			server.close();
			connector.close();
			connector = null;
			stop();
			JOptionPane.showMessageDialog(this, e.getMessage(),
					getTitle(), JOptionPane.WARNING_MESSAGE);
			return;
		}
	}

	@Override
	protected void onClose() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	public PortMapServerFrame() {
		super("Port Mapping Server", "Broadcast", 180, 72, true);

		JLabel lblPort = new JLabel("Port");
		lblPort.setBounds(6, 6, 36, 24);
		add(lblPort);

		txtMapPort.setBounds(42, 6, 42, 24);
		txtMapPort.enableInputMethods(false);
		add(txtMapPort);

		startButton.setBounds(6, 36, 78, 30);
		exitButton.setBounds(96, 36, 78, 30);
	}

	public static void main(String[] args) {
		invoke(PortMapServerFrame.class);
	}
}