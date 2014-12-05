package com.xqbase.net.tools;

import java.io.IOException;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

import com.xqbase.net.Connector;
import com.xqbase.net.misc.BroadcastServer;

public class BroadcastFrame extends ConnectorFrame {
	private static final long serialVersionUID = 1L;

	private JTextField txtPort = new JTextField("23");
	private JCheckBox chkNoEcho = new JCheckBox("No Echo");

	void stop() {
		trayIcon.setToolTip(getTitle());
		startMenuItem.setLabel("Start");
		startButton.setText("Start");
		txtPort.setEnabled(true);
		chkNoEcho.setEnabled(true);
	}

	@Override
	protected void start() {
		if (connector != null) {
			connector.close();
			connector = null;
			stop();
			return;
		}
		trayIcon.setToolTip(getTitle() + " (" + txtPort.getText() + ")");
		startMenuItem.setLabel("Stop");
		startButton.setText("Stop");
		txtPort.setEnabled(false);
		chkNoEcho.setEnabled(false);
		connector = new Connector();
		try {
			connector.add(new BroadcastServer(chkNoEcho.isSelected()),
					Integer.parseInt(txtPort.getText()));
		} catch (IOException | IllegalArgumentException e) {
			connector.close();
			connector = null;
			stop();
			JOptionPane.showMessageDialog(BroadcastFrame.this, e.getMessage(),
					getTitle(), JOptionPane.WARNING_MESSAGE);
			return;
		}
	}

	public BroadcastFrame() {
		super("Broadcast", "Broadcast", 180, 72, true);

		JLabel lblPort = new JLabel("Port");
		lblPort.setBounds(6, 6, 36, 24);
		add(lblPort);

		txtPort.setBounds(42, 6, 42, 24);
		txtPort.enableInputMethods(false);
		add(txtPort);

		chkNoEcho.setBounds(96, 6, 96, 24);
		add(chkNoEcho);

		startButton.setBounds(6, 36, 78, 30);
		exitButton.setBounds(96, 36, 78, 30);
	}

	public static void main(String[] args) {
		invoke(BroadcastFrame.class);
	}
}