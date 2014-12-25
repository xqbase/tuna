package com.xqbase.net.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.function.Supplier;

import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.JTextField;

import com.xqbase.net.ConnectorImpl;
import com.xqbase.net.misc.BandwidthFilter;
import com.xqbase.net.misc.DumpFilter;
import com.xqbase.net.misc.ForwardServer;
import com.xqbase.util.Numbers;

public class ForwardFrame extends ConnectorFrame {
	private static final long serialVersionUID = 1L;

	private static final String DUMP_NONE = "None";
	private static final String DUMP_BINARY = "Binary";
	private static final String DUMP_TEXT = "Text";
	private static final String DUMP_FOLDER = "Folder";

	private static final int SLIDER_NO_LIMIT = 4;

	private JSlider slider = new JSlider(0, SLIDER_NO_LIMIT, SLIDER_NO_LIMIT);
	private JTextField txtPort = new JTextField("23");
	private JTextField txtRemoteHost = new JTextField("localhost");
	private JTextField txtRemotePort = new JTextField("2323");
	private JComboBox<String> cmbDump = new JComboBox<>(new
			String[] {DUMP_NONE, DUMP_BINARY, DUMP_TEXT, DUMP_FOLDER});
	private JFileChooser chooser = new JFileChooser();

	private PrintStream dumpStream = null;
	private File dumpFolder = null;
	private boolean dumpText = false;

	long limit = 0;

	private Supplier<BandwidthFilter> bandwidth = () -> {
		BandwidthFilter filter = new BandwidthFilter() {
			@Override
			public long getLimit() {
				return limit;
			}
		};
		filter.setPeriod(1000);
		return filter;
	};
	private Supplier<DumpFilter> dump = () -> new DumpFilter().
			setDumpStream(dumpStream).setDumpFolder(dumpFolder).setDumpText(dumpText);

	void choose() {
		String selected = (String) cmbDump.getSelectedItem();
		if (selected.equals(DUMP_FOLDER)) {
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int retVal = chooser.showOpenDialog(ForwardFrame.this);
			dumpStream = null;
			if (retVal == JFileChooser.APPROVE_OPTION) {
				dumpFolder = chooser.getSelectedFile();
			} else {
				cmbDump.setSelectedItem(DUMP_NONE);
				dumpFolder = null;
			}
			dumpText = false;
		} else {
			dumpStream = selected.equals(DUMP_NONE) ? null : System.out;
			dumpFolder = null;
			dumpText = selected.equals(DUMP_TEXT);
		}
	}

	void stop() {
		trayIcon.setToolTip(getTitle());
		startMenuItem.setLabel("Start");
		startButton.setText("Start");
		txtPort.setEnabled(true);
		txtRemoteHost.setEnabled(true);
		txtRemotePort.setEnabled(true);
	}

	@Override
	protected void start() {
		if (connector != null) {
			connector.close();
			connector = null;
			stop();
			return;
		}
		trayIcon.setToolTip(String.format(getTitle() + " (%s->%s/%s)",
				txtPort.getText(), txtRemoteHost.getText(), txtRemotePort.getText()));
		startMenuItem.setLabel("Stop");
		startButton.setText("Stop");
		txtPort.setEnabled(false);
		txtRemoteHost.setEnabled(false);
		txtRemotePort.setEnabled(false);

		connector = new ConnectorImpl();
		try {
			ForwardServer forward = new ForwardServer(connector, txtRemoteHost.getText(),
					Numbers.parseInt(txtRemotePort.getText()));
			forward.appendRemoteFilter(bandwidth);
			connector.add(forward.appendFilter(dump).appendFilter(bandwidth),
					Numbers.parseInt(txtPort.getText()));
		} catch (IOException e) {
			connector.close();
			connector = null;
			stop();
			JOptionPane.showMessageDialog(this, e.getMessage(),
					getTitle(), JOptionPane.WARNING_MESSAGE);
		}
	}

	public ForwardFrame() {
		super("Forward", "Terminal", 288, 102, true);

		JLabel lblLimit = new JLabel("Speed Limit");
		lblLimit.setBounds(6, 6, 96, 24);
		add(lblLimit);

		final JLabel lblSlider = new JLabel("No Limit");
		lblSlider.setBounds(180, 6, 84, 24);
		add(lblSlider);

		slider.setBounds(90, 6, 78, 24);
		slider.setSnapToTicks(true);
		slider.addChangeListener(e -> {
			int sliderValue = ((JSlider) e.getSource()).getValue();
			limit = (1 << (sliderValue << 1));
			lblSlider.setText(sliderValue == SLIDER_NO_LIMIT ?
					"No Limit" : limit + "KB/s");
			if (sliderValue == SLIDER_NO_LIMIT) {
				limit = 0;
			} else {
				limit <<= 10;
			}
		});
		add(slider);

		JLabel lblPort = new JLabel("Port");
		lblPort.setBounds(6, 36, 36, 24);
		add(lblPort);

		txtPort.setBounds(42, 36, 42, 24);
		txtPort.enableInputMethods(false);
		add(txtPort);

		JLabel lblRemote = new JLabel("Remote");
		lblRemote.setBounds(96, 36, 48, 24);
		add(lblRemote);

		txtRemoteHost.setBounds(144, 36, 84, 24);
		txtRemoteHost.enableInputMethods(false);
		add(txtRemoteHost);

		JLabel lblSlash = new JLabel("/");
		lblSlash.setBounds(234, 36, 12, 24);
		add(lblSlash);

		txtRemotePort.setBounds(246, 36, 36, 24);
		txtRemotePort.enableInputMethods(false);
		add(txtRemotePort);

		JLabel lblDump = new JLabel("Dump");
		lblDump.setBounds(6, 66, 36, 24);
		add(lblDump);

		cmbDump.setBounds(42, 66, 72, 24);
		cmbDump.setEditable(false);
		cmbDump.setSelectedItem(DUMP_NONE);
		cmbDump.addActionListener(e -> choose());
		add(cmbDump);

		startButton.setBounds(120, 66, 78, 30);
		exitButton.setBounds(204, 66, 78, 30);
	}

	public static void main(String[] args) {
		invoke(ForwardFrame.class);
	}
}