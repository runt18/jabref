package org.jabref.gui.openoffice;

import java.awt.BorderLayout;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

import org.jabref.gui.worker.AbstractWorker;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.openoffice.OpenOfficeFileSearch;
import org.jabref.logic.openoffice.OpenOfficePreferences;
import org.jabref.logic.util.OS;
import org.jabref.logic.util.io.FileUtil;

import com.jgoodies.forms.builder.FormBuilder;
import com.jgoodies.forms.layout.FormLayout;

/**
 * Tools for automatically detecting OpenOffice or LibreOffice installations.
 */
public class DetectOpenOfficeInstallation extends AbstractWorker {

    private final OpenOfficePreferences preferences;

    private final JDialog parent;
    private boolean foundPaths;
    private JDialog progressDialog;

    private final OpenOfficeFileSearch fileSearch = new OpenOfficeFileSearch();

    public DetectOpenOfficeInstallation(JDialog parent, OpenOfficePreferences preferences) {
        this.parent = parent;
        this.preferences = preferences;
    }

    public boolean runDetection() {
        foundPaths = false;
        if (preferences.checkAutoDetectedPaths()) {
            return true;
        }
        init();
        getWorker().run();
        update();
        return foundPaths;
    }

    @Override
    public void run() {
        foundPaths = autoDetectPaths();
    }

    @Override
    public void init() {
        progressDialog = showProgressDialog(parent, Localization.lang("Autodetecting paths..."),
                Localization.lang("Please wait..."));
    }

    @Override
    public void update() {
        progressDialog.dispose();
    }

    private List<Path> detectInstallations() {
        if (OS.WINDOWS) {
            List<Path> programDirs = fileSearch.findWindowsOpenOfficeDirs();
            return programDirs.stream().filter(dir -> FileUtil.find(OpenOfficePreferences.WINDOWS_EXECUTABLE, dir).isPresent()).collect(Collectors.toList());
        } else if (OS.OS_X) {
            List<Path> programDirs = fileSearch.findOSXOpenOfficeDirs();
            return programDirs.stream().filter(dir -> FileUtil.find(OpenOfficePreferences.OSX_EXECUTABLE, dir).isPresent()).collect(Collectors.toList());
        } else if (OS.LINUX) {
            List<Path> programDirs = fileSearch.findLinuxOpenOfficeDirs();
            return programDirs.stream().filter(dir -> FileUtil.find(OpenOfficePreferences.LINUX_EXECUTABLE, dir).isPresent()).collect(Collectors.toList());
        } else {
            return new ArrayList<>(0);
        }
    }

    private Optional<Path> selectInstallationPath() {
        JOptionPane.showMessageDialog(parent,
                Localization.lang("Unable to autodetect OpenOffice/LibreOffice installation. Please choose the installation directory manually."),
                Localization.lang("Could not find OpenOffice/LibreOffice installation"),
                JOptionPane.INFORMATION_MESSAGE);
        // TODO Dialog service
        JFileChooser fileChooser = new JFileChooser(new File(System.getenv("ProgramFiles")));
        fileChooser.setDialogType(JFileChooser.OPEN_DIALOG);
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }

            @Override
            public String getDescription() {
                return Localization.lang("Directories");
            }
        });
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.showOpenDialog(parent);

        if (fileChooser.getSelectedFile() != null) {
            return Optional.of(Paths.get(fileChooser.getSelectedFile().getParent()));
        }
        return Optional.empty();
    }

    private boolean autoDetectPaths() {
        List<Path> installations = detectInstallations();

        // manually add installation path
        if (installations.isEmpty()) {
            selectInstallationPath().ifPresent(installations::add);
        }

        // select among multiple installations
        Optional<Path> actualFile = chooseAmongInstallations(installations);
        if (actualFile.isPresent()) {
            return setOpenOfficePreferences(actualFile.get());
        }

        return false;
    }

    private boolean setOpenOfficePreferences(Path installDir) {
        Optional<Path> execPath = Optional.empty();

        if (OS.WINDOWS) {
            execPath = FileUtil.find(OpenOfficePreferences.WINDOWS_EXECUTABLE, installDir);
        } else if (OS.OS_X) {
            execPath = FileUtil.find(OpenOfficePreferences.OSX_EXECUTABLE, installDir);
        } else if (OS.LINUX) {
            execPath = FileUtil.find(OpenOfficePreferences.LINUX_EXECUTABLE, installDir);
        }

        Optional<Path> jarPath = FileUtil.find(OpenOfficePreferences.OO_JARS.get(0), installDir);

        if (execPath.isPresent() && jarPath.isPresent()) {
            preferences.setOOPath(installDir.toString());
            preferences.setExecutablePath(execPath.get().toString());
            preferences.setJarsPath(jarPath.get().toString());
            return true;
        }

        return false;
    }

    private Optional<Path> chooseAmongInstallations(List<Path> installDirs) {
        if (installDirs.isEmpty()) {
            return Optional.empty();
        }

        if (installDirs.size() == 1) {
            return Optional.of(installDirs.get(0).toAbsolutePath());
        }
        // Otherwise more than one installation was found, select among them
        DefaultListModel<File> mod = new DefaultListModel<>();
        for (Path tmpfile : installDirs) {
            mod.addElement(tmpfile.toFile());
        }
        JList<File> fileList = new JList<>(mod);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setSelectedIndex(0);
        FormBuilder builder = FormBuilder.create().layout(new FormLayout("left:pref", "pref, 2dlu, pref, 4dlu, pref"));
        builder.add(Localization.lang("Found more than one OpenOffice/LibreOffice executable.")).xy(1, 1);
        builder.add(Localization.lang("Please choose which one to connect to:")).xy(1, 3);
        builder.add(fileList).xy(1, 5);
        int answer = JOptionPane.showConfirmDialog(null, builder.getPanel(),
                Localization.lang("Choose OpenOffice/LibreOffice executable"), JOptionPane.OK_CANCEL_OPTION);
        if (answer == JOptionPane.CANCEL_OPTION) {
            return Optional.empty();
        } else {
            return Optional.of(Paths.get(fileList.getSelectedValue().getParent()));
        }
    }

    public JDialog showProgressDialog(JDialog progressParent, String title, String message) {
        JProgressBar bar = new JProgressBar(SwingConstants.HORIZONTAL);
        final JDialog progressDialog = new JDialog(progressParent, title, false);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        bar.setIndeterminate(true);
        progressDialog.add(new JLabel(message), BorderLayout.NORTH);
        progressDialog.add(bar, BorderLayout.CENTER);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(null);
        progressDialog.setVisible(true);
        return progressDialog;
    }
}