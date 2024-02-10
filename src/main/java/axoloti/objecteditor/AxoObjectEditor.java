/**
 * Copyright (C) 2013 - 2016 Johannes Taelman
 *
 * This file is part of Axoloti.
 *
 * Axoloti is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Axoloti is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Axoloti. If not, see <http://www.gnu.org/licenses/>.
 */
package axoloti.objecteditor;

import axoloti.DocumentWindow;
import axoloti.DocumentWindowList;
import axoloti.MainFrame;
import axoloti.attributedefinition.AxoAttribute;
import axoloti.displays.Display;
import axoloti.inlets.Inlet;
import axoloti.object.AxoObject;
import axoloti.object.AxoObjectAbstract;
import axoloti.object.AxoObjectInstance;
import axoloti.object.ObjectModifiedListener;
import axoloti.outlets.Outlet;
import axoloti.parameters.Parameter;
import axoloti.utils.AxolotiLibrary;
import axoloti.utils.Constants;
import components.ScrollPaneComponent;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

/**
 *
 * @author Johannes Taelman
 */
public final class AxoObjectEditor extends JFrame implements DocumentWindow, ObjectModifiedListener {

    final AxoObject editObj;
    private String origXML;
    private final RSyntaxTextArea jTextAreaLocalData;
    private final RSyntaxTextArea jTextAreaInitCode;
    private final RSyntaxTextArea jTextAreaKRateCode;
    private final RSyntaxTextArea jTextAreaSRateCode;
    private final RSyntaxTextArea jTextAreaDisposeCode;
    private final RSyntaxTextArea jTextAreaMidiCode;

    private boolean readonly = false;
    private AxoCompletionProvider acProvider;

    static RSyntaxTextArea initCodeEditor(JPanel p, AxoCompletionProvider acpr) {
        RSyntaxTextArea rsta = new RSyntaxTextArea(20, 60);

        try {
            Theme theme = Theme.load(Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/monokai.xml"));
            theme.apply(rsta);
        } catch (Exception e) {
            e.printStackTrace();
        }

        rsta.setFont(Constants.FONT_MONO);
        rsta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS);
        rsta.setCodeFoldingEnabled(true);
        rsta.setAntiAliasingEnabled(true);
        rsta.setAutoIndentEnabled(true);
        rsta.setMarkOccurrences(true);
        rsta.setMarkOccurrencesColor(new Color(0x00,0x00,0x00, 0x60));
        rsta.setPaintTabLines(true);
        // rsta.setLineWrap(true);

        RTextScrollPane sp = new RTextScrollPane(rsta);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        sp.getHorizontalScrollBar().setUnitIncrement(10);
        sp.getVerticalScrollBar().setUnitIncrement(10);
        p.setLayout(new BorderLayout());
        p.add(sp);

        rsta.setVisible(true);
        rsta.setToolTipText(null);

        AutoCompletion ac = new AutoCompletion(acpr);
        ac.setAutoCompleteEnabled(true);
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(500);
        ac.setShowDescWindow(false);
        ac.install(rsta);
        return rsta;
    }

    private abstract class DocumentChangeListener implements DocumentListener {

        abstract void update();

        @Override
        public void insertUpdate(DocumentEvent e) {
            update();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            update();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            update();
        }
    }

    String CleanString(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s;
    }

    public void updateReferenceXML() {
        Serializer serializer = new Persister();
        ByteArrayOutputStream origOS = new ByteArrayOutputStream(2048);
        try {
            serializer.write(editObj, origOS);
        } catch (Exception ex) {
            Logger.getLogger(AxoObjectEditor.class.getName()).log(Level.SEVERE, null, ex);
        }
        origXML = origOS.toString();
    }

    void Revert() {
        try {
            Serializer serializer = new Persister();
            AxoObject objrev = serializer.read(AxoObject.class, origXML);
            editObj.copy(objrev);
            editObj.FireObjectModified(this);
            Close();

        } catch (Exception ex) {
            Logger.getLogger(AxoObjectEditor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public AxoObjectEditor(final AxoObject origObj) {
        initComponents();

        acProvider = new AxoCompletionProvider();

        fileMenu1.initComponents();
        DocumentWindowList.RegisterWindow(this);
        jTextAreaLocalData = initCodeEditor(jPanelLocalData, acProvider);
        jTextAreaInitCode = initCodeEditor(jPanelInitCode, acProvider);
        jTextAreaKRateCode = initCodeEditor(jPanelKRateCode2, acProvider);
        jTextAreaSRateCode = initCodeEditor(jPanelSRateCode, acProvider);
        jTextAreaDisposeCode = initCodeEditor(jPanelDisposeCode, acProvider);
        jTextAreaMidiCode = initCodeEditor(jPanelMidiCode2, acProvider);
        setIconImage(new ImageIcon(getClass().getResource("/resources/ksoloti_icon_axo.png")).getImage());
        editObj = origObj;
        updateAcProvider(editObj);



        initEditFromOrig();
        updateReferenceXML();
        inletDefinitionsEditor1.initComponents(editObj);
        outletDefinitionsEditorPanel1.initComponents(editObj);
        paramDefinitionsEditorPanel1.initComponents(editObj);
        attributeDefinitionsEditorPanel1.initComponents(editObj);
        displayDefinitionsEditorPanel1.initComponents(editObj);

        jTextFieldAuthor.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sAuthor = jTextFieldAuthor.getText().trim();
                editObj.FireObjectModified(this);
            }
        });

        jTextFieldLicense.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sLicense = jTextFieldLicense.getText().trim();
                editObj.FireObjectModified(this);
            }
        });

        jTextFieldHelp.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.helpPatch = jTextFieldHelp.getText().trim();
                editObj.FireObjectModified(this);
            }
        });

        jTextDesc.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sDescription = jTextDesc.getText().trim();
                editObj.FireObjectModified(this);
            }
        });

        jLabelMidiPrototype.setText(AxoObjectInstance.MidiHandlerFunctionHeader);

        jTextAreaLocalData.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sLocalData = CleanString(jTextAreaLocalData.getText());
            }
        });
        jTextAreaInitCode.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sInitCode = CleanString(jTextAreaInitCode.getText());
            }
        });
        jTextAreaKRateCode.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sKRateCode = CleanString(jTextAreaKRateCode.getText());
            }
        });
        jTextAreaSRateCode.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sSRateCode = CleanString(jTextAreaSRateCode.getText());
            }
        });
        jTextAreaDisposeCode.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sDisposeCode = CleanString(jTextAreaDisposeCode.getText());
            }
        });
        jTextAreaMidiCode.getDocument().addDocumentListener(new DocumentChangeListener() {
            @Override
            void update() {
                editObj.sMidiCode = CleanString(jTextAreaMidiCode.getText());
            }
        });
        rSyntaxTextAreaXML.setFont(Constants.FONT_MONO);
        rSyntaxTextAreaXML.setEditable(false);

        // is it from the factory?
        AxolotiLibrary sellib = null;
        for (AxolotiLibrary lib : MainFrame.prefs.getLibraries()) {
            if (editObj.sPath != null && editObj.sPath.startsWith(lib.getLocalLocation())) {

                if (sellib == null || sellib.getLocalLocation().length() < lib.getLocalLocation().length()) {
                    sellib = lib;
                }
            }
        }
        if (IsEmbeddedObj()) {
            jMenuItemSave.setEnabled(false);
            jLabelLibrary.setText("embedded");
            setTitle("");
            // embedded objects have no use for help patches
            jTextFieldHelp.setVisible(false);
            jLabelHelp.setVisible(false);
        } else // normal objects
        if (sellib != null) {
            jMenuItemSave.setEnabled(!sellib.isReadOnly());
            if (sellib.isReadOnly()) {
                SetReadOnly(true);
                jLabelLibrary.setText(sellib.getId() + " (readonly)");
                setTitle(sellib.getId() + ":" + origObj.id + " (readonly)");
            } else {
                jLabelLibrary.setText(sellib.getId());
                setTitle(sellib.getId() + ":" + origObj.id);
            }
        }

        editObj.FireObjectModified(this);
        jTextDesc.requestFocus();
    }

    boolean IsEmbeddedObj() {
        return (editObj.sPath == null || editObj.sPath.length() == 0);
    }

    void SetReadOnly(boolean readonly) {
        this.readonly = readonly;
        jTextDesc.setEditable(!readonly);
        jTextFieldAuthor.setEditable(!readonly);
        jTextFieldLicense.setEditable(!readonly);
        jTextFieldHelp.setEditable(!readonly);
        jTextAreaLocalData.setEditable(!readonly);
        jTextAreaInitCode.setEditable(!readonly);
        jTextAreaKRateCode.setEditable(!readonly);
        jTextAreaSRateCode.setEditable(!readonly);
        jTextAreaDisposeCode.setEditable(!readonly);
        jTextAreaMidiCode.setEditable(!readonly);
        inletDefinitionsEditor1.setEditable(!readonly);
        outletDefinitionsEditorPanel1.setEditable(!readonly);
        paramDefinitionsEditorPanel1.setEditable(!readonly);
        attributeDefinitionsEditorPanel1.setEditable(!readonly);
        displayDefinitionsEditorPanel1.setEditable(!readonly);
    }

    void initFields() {
        jLabelName.setText(editObj.getCName());
        jTextFieldLicense.setText(editObj.sLicense);
        jTextDesc.setText(editObj.sDescription);
        jTextFieldAuthor.setText(editObj.sAuthor);
        jTextFieldHelp.setText(editObj.helpPatch);

        ((DefaultListModel) jListIncludes.getModel()).removeAllElements();
        if (editObj.includes != null) {
            for (String i : editObj.includes) {
                ((DefaultListModel) jListIncludes.getModel()).addElement(i);
            }
        }

        ((DefaultListModel) jListIncludes.getModel()).removeAllElements();
        if (editObj.depends != null) {
            for (String i : editObj.depends) {
                ((DefaultListModel) jListDepends.getModel()).addElement(i);
            }
        }

        // this updates text editors
        ObjectModified(null);
    }

    boolean compareField(String oVal, String nVal) {
        String ov = oVal, nv = nVal;
        if (ov == null) {
            ov = "";
        }
        return ov.equals(nv);
    }

    boolean hasChanged() {
        updateAcProvider(editObj);
        Serializer serializer = new Persister();

        ByteArrayOutputStream editOS = new ByteArrayOutputStream(2048);
        try {
            serializer.write(editObj, editOS);
        } catch (Exception ex) {
            Logger.getLogger(AxoObjectEditor.class.getName()).log(Level.SEVERE, null, ex);
        }
        return !(origXML.equals(editOS.toString()));
    }

    @Override
    public void ObjectModified(Object source) {
        if (source != this) {
            jTextAreaLocalData.setText(editObj.sLocalData == null ? "" : editObj.sLocalData);
            jTextAreaInitCode.setText(editObj.sInitCode == null ? "" : editObj.sInitCode);
            jTextAreaKRateCode.setText(editObj.sKRateCode == null ? "" : editObj.sKRateCode);
            jTextAreaSRateCode.setText(editObj.sSRateCode == null ? "" : editObj.sSRateCode);
            jTextAreaDisposeCode.setText(editObj.sDisposeCode == null ? "" : editObj.sDisposeCode);
            jTextAreaMidiCode.setText(editObj.sMidiCode == null ? "" : editObj.sMidiCode);
        }
        Serializer serializer = new Persister();
        ByteArrayOutputStream os = new ByteArrayOutputStream(2048);
        try {
            serializer.write(editObj, os);
            Theme theme = Theme.load(Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/monokai.xml"));
            theme.apply(rSyntaxTextAreaXML);
        } catch (Exception ex) {
            Logger.getLogger(AxoObjectEditor.class.getName()).log(Level.SEVERE, null, ex);
        }
        rSyntaxTextAreaXML.setFont(Constants.FONT_MONO);
        rSyntaxTextAreaXML.setText(os.toString());
        rSyntaxTextAreaXML.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
        rSyntaxTextAreaXML.setCodeFoldingEnabled(true);
        updateAcProvider(editObj);

        AxoObjectInstance obji = editObj.CreateInstance(null, "test", new Point(0, 0));
    }

    public void initEditFromOrig() {
        editObj.addObjectModifiedListener(this);
        editObj.FireObjectModified(this);
        initFields();
    }

    @Override
    public boolean AskClose() {
        // if it's an embedded object ("patch/object"), assume the parent patch is saving
        if (IsEmbeddedObj()) {
            if (hasChanged()) {

            }
            Close();
            return false;
        }
        // warn if changes, and its not an embedded object
        if (hasChanged()) {
            if (!readonly) {
                Object[] options = {"Yes", "No", "Cancel"};
                int n = JOptionPane.showOptionDialog(
                        this,
                        "Save changes to \"" + editObj.getCName() + "\" ?",
                        "Unsaved Changes",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[0]);
                switch (n) {
                    case JOptionPane.YES_OPTION:
                        jMenuItemSaveActionPerformed(null);
                        Close();
                        return false;
                    case JOptionPane.NO_OPTION:
                        Revert();
                        Close();
                        return false;
                    case JOptionPane.CANCEL_OPTION:
                    default: // closed
                        return true;
                }
            } else {
                Logger.getLogger(AxoObjectEditor.class.getName()).log(Level.SEVERE, null, "Changed but readonly: should not happen");
                return true;
            }
        } else {
            // no changes
            Close();
            return false;
        }
    }

    public void Close() {
        DocumentWindowList.UnregisterWindow(this);
        editObj.removeObjectModifiedListener(this);
        editObj.removeObjectModifiedListener(attributeDefinitionsEditorPanel1);
        editObj.removeObjectModifiedListener(displayDefinitionsEditorPanel1);
        editObj.removeObjectModifiedListener(inletDefinitionsEditor1);
        editObj.removeObjectModifiedListener(outletDefinitionsEditorPanel1);
        editObj.removeObjectModifiedListener(paramDefinitionsEditorPanel1);
        dispose();
        editObj.CloseEditor();
    }

    public int getActiveTabIndex() {
        return this.jTabbedPane1.getSelectedIndex();
    }

    public void setActiveTabIndex(int n) {
        this.jTabbedPane1.setSelectedIndex(n);
    }

    boolean isCompositeObject() {
        if (editObj.sPath == null) {
            return false;
        }
        int count = 0;
        for (AxoObjectAbstract o : MainFrame.axoObjects.ObjectList) {
            if (editObj.sPath.equalsIgnoreCase(o.sPath)) {
                count++;
            }
        }
        return (count > 1);
    }

    void updateAcProvider(AxoObject obj) {
        /* add keywords to autocomplete list */
        if (obj == null) return;

        if (obj.inlets != null && obj.inlets.size() > 0) {
            for (Inlet i : obj.inlets) {
                acProvider.addACKeyword(i.GetCName());
            }
        }
        if (obj.outlets != null && obj.outlets.size() > 0) {
            for (Outlet o : obj.outlets) {
                acProvider.addACKeyword(o.GetCName());
            }
        }
        if (obj.attributes != null && obj.attributes.size() > 0) {
            for (AxoAttribute a : obj.attributes) {
                acProvider.addACKeyword(a.GetCName());
            }
        }
        if (obj.params != null && obj.params.size() > 0) {
            for (Parameter p : obj.params) {
                acProvider.addACKeyword(p.GetCName());
            }
        }
        if (obj.displays != null && obj.displays.size() > 0) {
            for (Display d : obj.displays) {
                acProvider.addACKeyword(d.GetCName());
            }
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    
    private void initComponents() {

        jInternalFrame1 = new javax.swing.JInternalFrame();
        jLabel4 = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        jTabbedPane1 = new javax.swing.JTabbedPane();
        jPanelOverview = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jLabelLibrary = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        jLabelName = new javax.swing.JLabel();
        jLabel8 = new javax.swing.JLabel();
        jTextFieldAuthor = new javax.swing.JTextField();
        jLabel9 = new javax.swing.JLabel();
        jTextFieldLicense = new javax.swing.JTextField();
        jLabelHelp = new javax.swing.JLabel();
        jTextFieldHelp = new javax.swing.JTextField();
        jPanel3 = new javax.swing.JPanel();
        jLabel10 = new javax.swing.JLabel();
        jScrollPane13 = new ScrollPaneComponent();
        jTextDesc = new javax.swing.JTextArea();
        jLabel5 = new javax.swing.JLabel();
        jScrollPane4 = new ScrollPaneComponent();
        jListIncludes = new javax.swing.JList();
        jLabel6 = new javax.swing.JLabel();
        jScrollPane12 = new ScrollPaneComponent();
        jListDepends = new javax.swing.JList();
        inletDefinitionsEditor1 = new axoloti.objecteditor.InletDefinitionsEditorPanel();
        outletDefinitionsEditorPanel1 = new axoloti.objecteditor.OutletDefinitionsEditorPanel();
        attributeDefinitionsEditorPanel1 = new axoloti.objecteditor.AttributeDefinitionsEditorPanel();
        paramDefinitionsEditorPanel1 = new axoloti.objecteditor.ParamDefinitionsEditorPanel();
        displayDefinitionsEditorPanel1 = new axoloti.objecteditor.DisplayDefinitionsEditorPanel();
        jPanelLocalData = new javax.swing.JPanel();
        jPanelInitCode = new javax.swing.JPanel();
        jPanelKRateCode = new javax.swing.JPanel();
        jPanelKRateCode2 = new javax.swing.JPanel();
        jPanelSRateCode = new javax.swing.JPanel();
        jPanelDisposeCode = new javax.swing.JPanel();
        jPanelMidiCode = new javax.swing.JPanel();
        jLabelMidiPrototype = new javax.swing.JLabel();
        jPanelMidiCode2 = new javax.swing.JPanel();
        jPanelXML = new javax.swing.JPanel();
        jScrollPane6 = new ScrollPaneComponent();
        rSyntaxTextAreaXML = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea();
        jLabel2 = new javax.swing.JLabel();
        jMenuBar1 = new javax.swing.JMenuBar();
        fileMenu1 = new axoloti.menus.FileMenu();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        jMenuItemSave = new javax.swing.JMenuItem();
        jMenuItemRevert = new javax.swing.JMenuItem();
        jMenuItemCopyToLibrary = new javax.swing.JMenuItem();
        windowMenu1 = new axoloti.menus.WindowMenu();
        helpMenu1 = new axoloti.menus.HelpMenu();

        jInternalFrame1.setVisible(true);

        javax.swing.GroupLayout jInternalFrame1Layout = new javax.swing.GroupLayout(jInternalFrame1.getContentPane());
        jInternalFrame1.getContentPane().setLayout(jInternalFrame1Layout);
        jInternalFrame1Layout.setHorizontalGroup(
            jInternalFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        jInternalFrame1Layout.setVerticalGroup(
            jInternalFrame1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setPreferredSize(new java.awt.Dimension(800, 480));
        addWindowFocusListener(new java.awt.event.WindowFocusListener() {
            public void windowGainedFocus(java.awt.event.WindowEvent evt) {
            }
            public void windowLostFocus(java.awt.event.WindowEvent evt) {
                formWindowLostFocus(evt);
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });
        getContentPane().setLayout(new javax.swing.BoxLayout(getContentPane(), javax.swing.BoxLayout.PAGE_AXIS));
        getContentPane().add(jLabel4);

        jPanel1.setPreferredSize(new java.awt.Dimension(640, 100));
        jPanel1.setLayout(new javax.swing.BoxLayout(jPanel1, javax.swing.BoxLayout.PAGE_AXIS));

        jTabbedPane1.setTabPlacement(javax.swing.JTabbedPane.LEFT);
        jTabbedPane1.setPreferredSize(new java.awt.Dimension(640, 100));
        jTabbedPane1.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        jPanelOverview.setLayout(new javax.swing.BoxLayout(jPanelOverview, javax.swing.BoxLayout.Y_AXIS));

        jPanel2.setLayout(new java.awt.GridLayout(5, 2));

        jLabel1.setText("Library:");
        jPanel2.add(jLabel1);

        jLabelLibrary.setText("library");
        jPanel2.add(jLabelLibrary);

        jLabel7.setText("Name:");
        jPanel2.add(jLabel7);

        jLabelName.setText("object name");
        jPanel2.add(jLabelName);

        jLabel8.setText("Author:");
        jPanel2.add(jLabel8);

        jTextFieldAuthor.setText("author");
        jPanel2.add(jTextFieldAuthor);

        jLabel9.setText("License:");
        jPanel2.add(jLabel9);

        jTextFieldLicense.setText("license");
        jPanel2.add(jTextFieldLicense);

        jLabelHelp.setText("Help patch");
        jPanel2.add(jLabelHelp);

        jTextFieldHelp.setText("help");
        jPanel2.add(jTextFieldHelp);

        jPanelOverview.add(jPanel2);

        jPanel3.setLayout(new javax.swing.BoxLayout(jPanel3, javax.swing.BoxLayout.Y_AXIS));

        jLabel10.setText("Description:");
        jPanel3.add(jLabel10);

        jTextDesc.setColumns(20);
        jTextDesc.setLineWrap(true);
        jTextDesc.setRows(5);
        jTextDesc.setWrapStyleWord(true);
        jScrollPane13.setViewportView(jTextDesc);

        jPanel3.add(jScrollPane13);

        jLabel5.setText("Includes");
        jPanel3.add(jLabel5);

        jListIncludes.setModel(new DefaultListModel());
        jScrollPane4.setViewportView(jListIncludes);

        jPanel3.add(jScrollPane4);

        jLabel6.setText("Dependencies");
        jPanel3.add(jLabel6);

        jListDepends.setModel(new DefaultListModel());
        jScrollPane12.setViewportView(jListDepends);

        jPanel3.add(jScrollPane12);

        jPanelOverview.add(jPanel3);

        jTabbedPane1.addTab("Overview", jPanelOverview);

        javax.swing.GroupLayout inletDefinitionsEditor1Layout = new javax.swing.GroupLayout(inletDefinitionsEditor1);
        inletDefinitionsEditor1.setLayout(inletDefinitionsEditor1Layout);
        inletDefinitionsEditor1Layout.setHorizontalGroup(
            inletDefinitionsEditor1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        inletDefinitionsEditor1Layout.setVerticalGroup(
            inletDefinitionsEditor1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Inlets", inletDefinitionsEditor1);

        javax.swing.GroupLayout outletDefinitionsEditorPanel1Layout = new javax.swing.GroupLayout(outletDefinitionsEditorPanel1);
        outletDefinitionsEditorPanel1.setLayout(outletDefinitionsEditorPanel1Layout);
        outletDefinitionsEditorPanel1Layout.setHorizontalGroup(
            outletDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        outletDefinitionsEditorPanel1Layout.setVerticalGroup(
            outletDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Outlets", outletDefinitionsEditorPanel1);

        javax.swing.GroupLayout attributeDefinitionsEditorPanel1Layout = new javax.swing.GroupLayout(attributeDefinitionsEditorPanel1);
        attributeDefinitionsEditorPanel1.setLayout(attributeDefinitionsEditorPanel1Layout);
        attributeDefinitionsEditorPanel1Layout.setHorizontalGroup(
            attributeDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        attributeDefinitionsEditorPanel1Layout.setVerticalGroup(
            attributeDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Attributes", attributeDefinitionsEditorPanel1);

        javax.swing.GroupLayout paramDefinitionsEditorPanel1Layout = new javax.swing.GroupLayout(paramDefinitionsEditorPanel1);
        paramDefinitionsEditorPanel1.setLayout(paramDefinitionsEditorPanel1Layout);
        paramDefinitionsEditorPanel1Layout.setHorizontalGroup(
            paramDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        paramDefinitionsEditorPanel1Layout.setVerticalGroup(
            paramDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Parameters", paramDefinitionsEditorPanel1);

        javax.swing.GroupLayout displayDefinitionsEditorPanel1Layout = new javax.swing.GroupLayout(displayDefinitionsEditorPanel1);
        displayDefinitionsEditorPanel1.setLayout(displayDefinitionsEditorPanel1Layout);
        displayDefinitionsEditorPanel1Layout.setHorizontalGroup(
            displayDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        displayDefinitionsEditorPanel1Layout.setVerticalGroup(
            displayDefinitionsEditorPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Displays", displayDefinitionsEditorPanel1);

        javax.swing.GroupLayout jPanelLocalDataLayout = new javax.swing.GroupLayout(jPanelLocalData);
        jPanelLocalData.setLayout(jPanelLocalDataLayout);
        jPanelLocalDataLayout.setHorizontalGroup(
            jPanelLocalDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelLocalDataLayout.setVerticalGroup(
            jPanelLocalDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Local Data", jPanelLocalData);

        javax.swing.GroupLayout jPanelInitCodeLayout = new javax.swing.GroupLayout(jPanelInitCode);
        jPanelInitCode.setLayout(jPanelInitCodeLayout);
        jPanelInitCodeLayout.setHorizontalGroup(
            jPanelInitCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelInitCodeLayout.setVerticalGroup(
            jPanelInitCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Init Code", jPanelInitCode);

        jPanelKRateCode.setLayout(new javax.swing.BoxLayout(jPanelKRateCode, javax.swing.BoxLayout.Y_AXIS));

        javax.swing.GroupLayout jPanelKRateCode2Layout = new javax.swing.GroupLayout(jPanelKRateCode2);
        jPanelKRateCode2.setLayout(jPanelKRateCode2Layout);
        jPanelKRateCode2Layout.setHorizontalGroup(
            jPanelKRateCode2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelKRateCode2Layout.setVerticalGroup(
            jPanelKRateCode2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jPanelKRateCode.add(jPanelKRateCode2);

        jTabbedPane1.addTab("K-rate Code", jPanelKRateCode);

        javax.swing.GroupLayout jPanelSRateCodeLayout = new javax.swing.GroupLayout(jPanelSRateCode);
        jPanelSRateCode.setLayout(jPanelSRateCodeLayout);
        jPanelSRateCodeLayout.setHorizontalGroup(
            jPanelSRateCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelSRateCodeLayout.setVerticalGroup(
            jPanelSRateCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("S-rate Code", jPanelSRateCode);

        javax.swing.GroupLayout jPanelDisposeCodeLayout = new javax.swing.GroupLayout(jPanelDisposeCode);
        jPanelDisposeCode.setLayout(jPanelDisposeCodeLayout);
        jPanelDisposeCodeLayout.setHorizontalGroup(
            jPanelDisposeCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelDisposeCodeLayout.setVerticalGroup(
            jPanelDisposeCodeLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Dispose Code", jPanelDisposeCode);

        jPanelMidiCode.setLayout(new javax.swing.BoxLayout(jPanelMidiCode, javax.swing.BoxLayout.Y_AXIS));

        jLabelMidiPrototype.setText("jLabel11");
        jPanelMidiCode.add(jLabelMidiPrototype);

        javax.swing.GroupLayout jPanelMidiCode2Layout = new javax.swing.GroupLayout(jPanelMidiCode2);
        jPanelMidiCode2.setLayout(jPanelMidiCode2Layout);
        jPanelMidiCode2Layout.setHorizontalGroup(
            jPanelMidiCode2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 466, Short.MAX_VALUE)
        );
        jPanelMidiCode2Layout.setVerticalGroup(
            jPanelMidiCode2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 288, Short.MAX_VALUE)
        );

        jPanelMidiCode.add(jPanelMidiCode2);

        jTabbedPane1.addTab("MIDI Code", jPanelMidiCode);

        rSyntaxTextAreaXML.setColumns(20);
        rSyntaxTextAreaXML.setRows(5);
        jScrollPane6.setViewportView(rSyntaxTextAreaXML);

        javax.swing.GroupLayout jPanelXMLLayout = new javax.swing.GroupLayout(jPanelXML);
        jPanelXML.setLayout(jPanelXMLLayout);
        jPanelXMLLayout.setHorizontalGroup(
            jPanelXMLLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jScrollPane6, javax.swing.GroupLayout.DEFAULT_SIZE, 466, Short.MAX_VALUE)
        );
        jPanelXMLLayout.setVerticalGroup(
            jPanelXMLLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jScrollPane6, javax.swing.GroupLayout.DEFAULT_SIZE, 302, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("XML", jPanelXML);

        jPanel1.add(jTabbedPane1);

        jLabel2.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        jPanel1.add(jLabel2);

        getContentPane().add(jPanel1);

        fileMenu1.setText("File");
        fileMenu1.add(jSeparator1);

        jMenuItemSave.setText("Save");
        jMenuItemSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemSaveActionPerformed(evt);
            }
        });
        fileMenu1.add(jMenuItemSave);

        jMenuItemRevert.setText("Revert");
        jMenuItemRevert.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemRevertActionPerformed(evt);
            }
        });
        fileMenu1.add(jMenuItemRevert);

        jMenuItemCopyToLibrary.setText("Copy to Library...");
        jMenuItemCopyToLibrary.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItemCopyToLibraryActionPerformed(evt);
            }
        });
        fileMenu1.add(jMenuItemCopyToLibrary);

        jMenuBar1.add(fileMenu1);
        jMenuBar1.add(windowMenu1);

        helpMenu1.setText("Help");
        jMenuBar1.add(helpMenu1);

        setJMenuBar(jMenuBar1);

        pack();
    }

    private void formWindowClosing(java.awt.event.WindowEvent evt) {
        AskClose();
    }

    private void jMenuItemSaveActionPerformed(java.awt.event.ActionEvent evt) {
        editObj.FireObjectModified(this);
        if (!isCompositeObject()) {
            MainFrame.axoObjects.WriteAxoObject(editObj.sPath, editObj);
            updateReferenceXML();
            MainFrame.axoObjects.LoadAxoObjects();
        } else {
            JOptionPane.showMessageDialog(null, "The original object file " + editObj.sPath + " contains multiple objects, the object editor does not support this.\n"
                    + "Your changes are NOT saved!");
        }
    }

    private void jMenuItemCopyToLibraryActionPerformed(java.awt.event.ActionEvent evt) {
        AddToLibraryDlg dlg = new AddToLibraryDlg(this, true, editObj);
        dlg.setVisible(true);
        Close();
    }

    private void jMenuItemRevertActionPerformed(java.awt.event.ActionEvent evt) {
        Rectangle editorBounds = this.getBounds();
        int activeTabIndex = this.getActiveTabIndex();
        Revert();
        AxoObjectEditor axoObjectEditor = new AxoObjectEditor(editObj);
        axoObjectEditor.setBounds(editorBounds);
        axoObjectEditor.setActiveTabIndex(activeTabIndex);
        axoObjectEditor.setVisible(true);
    }

    private void formWindowLostFocus(java.awt.event.WindowEvent evt) {
        // TODO add your handling code here:
    }

    private axoloti.objecteditor.AttributeDefinitionsEditorPanel attributeDefinitionsEditorPanel1;
    private axoloti.objecteditor.DisplayDefinitionsEditorPanel displayDefinitionsEditorPanel1;
    private axoloti.menus.FileMenu fileMenu1;
    private axoloti.menus.HelpMenu helpMenu1;
    private axoloti.objecteditor.InletDefinitionsEditorPanel inletDefinitionsEditor1;
    private javax.swing.JInternalFrame jInternalFrame1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLabel jLabelHelp;
    private javax.swing.JLabel jLabelLibrary;
    private javax.swing.JLabel jLabelMidiPrototype;
    private javax.swing.JLabel jLabelName;
    private javax.swing.JList jListDepends;
    private javax.swing.JList jListIncludes;
    private javax.swing.JMenuBar jMenuBar1;
    private javax.swing.JMenuItem jMenuItemCopyToLibrary;
    private javax.swing.JMenuItem jMenuItemRevert;
    private javax.swing.JMenuItem jMenuItemSave;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanelDisposeCode;
    private javax.swing.JPanel jPanelInitCode;
    private javax.swing.JPanel jPanelKRateCode;
    private javax.swing.JPanel jPanelKRateCode2;
    private javax.swing.JPanel jPanelLocalData;
    private javax.swing.JPanel jPanelMidiCode;
    private javax.swing.JPanel jPanelMidiCode2;
    private javax.swing.JPanel jPanelOverview;
    private javax.swing.JPanel jPanelSRateCode;
    private javax.swing.JPanel jPanelXML;
    private ScrollPaneComponent jScrollPane12;
    private ScrollPaneComponent jScrollPane13;
    private ScrollPaneComponent jScrollPane4;
    private ScrollPaneComponent jScrollPane6;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JTextArea jTextDesc;
    private javax.swing.JTextField jTextFieldAuthor;
    private javax.swing.JTextField jTextFieldHelp;
    private javax.swing.JTextField jTextFieldLicense;
    private axoloti.objecteditor.OutletDefinitionsEditorPanel outletDefinitionsEditorPanel1;
    private axoloti.objecteditor.ParamDefinitionsEditorPanel paramDefinitionsEditorPanel1;
    private org.fife.ui.rsyntaxtextarea.RSyntaxTextArea rSyntaxTextAreaXML;
    private axoloti.menus.WindowMenu windowMenu1;

    @Override
    public JFrame GetFrame() {
        return this;
    }

    @Override
    public File getFile() {
        return null;
    }

    @Override
    public ArrayList<DocumentWindow> GetChildDocuments() {
        return null;
    }
}
