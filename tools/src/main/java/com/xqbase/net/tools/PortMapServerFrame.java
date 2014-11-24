package com.xqbase.net.tools;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.JTextField;

import com.xqbase.net.misc.DoSFilterFactory;
import com.xqbase.net.portmap.PortMapServer;
import com.xqbase.util.Runnables;

public class PortMapServerFrame extends ConnectorFrame {
	private static final long serialVersionUID = 1L;

	private static final int SLIDER_NO_LIMIT = 4;

	private JSlider slider = new JSlider(0, SLIDER_NO_LIMIT, SLIDER_NO_LIMIT);
	private JTextField txtPortFrom = new JTextField("8000");
	private JTextField txtPortTo = new JTextField("9000");
	private JTextField txtMapPort = new JTextField("8341");
	private JTextField txtRequests = new JTextField("0");
	private JTextField txtConnections = new JTextField("0");

	private DoSFilterFactory dosff = new DoSFilterFactory(65536, 0, 0, 0);
	private PortMapServer server = null;
	private ScheduledThreadPoolExecutor timer;

	void stop() {
		trayIcon.setToolTip(getTitle());
		startMenuItem.setLabel("Start");
		startButton.setText("Start");
		txtPortFrom.setEnabled(true);
		txtPortTo.setEnabled(true);
		txtMapPort.setEnabled(true);
		txtRequests.setEnabled(true);
		txtConnections.setEnabled(true);
	}

	@Override
	protected void start() {
		if (server != null) {
			Runnables.shutdown(timer);
			connector.remove(server);
			server = null;
			stop();
			return;
		}
		trayIcon.setToolTip(String.format(getTitle() + " (%s %s-%s)", txtMapPort.getText(),
				txtPortFrom.getText(), txtPortTo.getText()));
		startMenuItem.setLabel("Stop");
		startButton.setText("Stop");
		txtPortFrom.setEnabled(false);
		txtPortTo.setEnabled(false);
		txtMapPort.setEnabled(false);
		txtRequests.setEnabled(false);
		txtConnections.setEnabled(false);

		timer = new ScheduledThreadPoolExecutor(1);
		int requests, connections;
		try {
			requests = Integer.parseInt(txtRequests.getText());
			connections = Integer.parseInt(txtConnections.getText());
			server = new PortMapServer(connector, Integer.parseInt(txtMapPort.getText()),
					Integer.parseInt(txtPortFrom.getText()),
					Integer.parseInt(txtPortTo.getText()), timer);
		} catch (IOException | IllegalArgumentException e) {
			Runnables.shutdown(timer);
			stop();
			JOptionPane.showMessageDialog(this, e.getMessage(),
					getTitle(), JOptionPane.WARNING_MESSAGE);
			return;
		}
		connector.add(server);
		dosff.setParameters(65536, 0, requests, connections);
	}

	@Override
	protected void onEvent() {
		dosff.onTimer();
	}

	@Override
	protected void onClose() {
		if (server != null) {
			Runnables.shutdown(timer);
			server = null;
		}
	}

	public PortMapServerFrame() {
		super("Port Mapping Server", "Broadcast", 288, 156, true);

		JLabel lblLimit = new JLabel("Speed Limit");
		lblLimit.setBounds(5, 5, 105, 20);
		add(lblLimit);

		final JLabel lblSlider = new JLabel("No Limit");
		lblSlider.setBounds(185, 5, 70, 20);
		add(lblSlider);

		slider.setBounds(110, 5, 65, 20);
		slider.setSnapToTicks(true);
		slider.addChangeListener(e -> {
			int sliderValue = ((JSlider) e.getSource()).getValue();
			lblSlider.setText(sliderValue == SLIDER_NO_LIMIT ?
					"No Limit" : (1 << (sliderValue << 1)) + "KB/s");
			// TODO Speed Limit
		});
		add(slider);

		JLabel lblRemote = new JLabel("Public Port Range");
		lblRemote.setBounds(6, 36, 126, 24);
		add(lblRemote);

		txtPortFrom.setBounds(138, 36, 42, 24);
		txtPortFrom.enableInputMethods(false);
		add(txtPortFrom);

		JLabel lblDash = new JLabel("-");
		lblDash.setBounds(186, 36, 12, 24);
		add(lblDash);

		txtPortTo.setBounds(198, 36, 42, 24);
		txtPortTo.enableInputMethods(false);
		add(txtPortTo);

		JLabel lblPort = new JLabel("Mapping Port");
		lblPort.setBounds(6, 66, 126, 24);
		add(lblPort);

		txtMapPort.setBounds(138, 66, 42, 24);
		txtMapPort.enableInputMethods(false);
		add(txtMapPort);

		JLabel lblRequests = new JLabel("Requests / Minute");
		lblRequests.setBounds(6, 96, 126, 24);
		add(lblRequests);

		txtRequests.setBounds(138, 96, 42, 24);
		txtRequests.setToolTipText("0 = No Limit");
		txtRequests.enableInputMethods(false);
		add(txtRequests);

		JLabel lblConnections = new JLabel("Connections / IP");
		lblConnections.setBounds(6, 126, 126, 24);
		add(lblConnections);

		txtConnections.setBounds(138, 126, 42, 24);
		txtConnections.setToolTipText("0 = No Limit");
		txtConnections.enableInputMethods(false);
		add(txtConnections);

		startButton.setBounds(198, 84, 78, 30);
		exitButton.setBounds(198, 120, 78, 30);

		connector.getFilterFactories().add(dosff);
	}

	public static void main(String[] args) {
		invoke(PortMapServerFrame.class);
	}
}