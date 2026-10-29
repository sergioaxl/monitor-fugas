package com.mycompany.prueba;

import com.fazecast.jSerialComm.SerialPort;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

/**
 * Monitor de fugas por frecuencia.
 *
 * Protocolo esperado desde el microcontrolador (por Bluetooth / puerto serie, 9600 baudios):
 * una frecuencia en Hz por línea, terminada en salto de línea. Ejemplo: "3150\r\n"
 *
 * Si la frecuencia cae dentro de la banda configurada durante N muestras seguidas,
 * se declara FUGA. Cuando sale de la banda N muestras seguidas, la fuga se da por terminada.
 */
public class VentanaGrafica extends JFrame {

    // ===================== CONFIGURACIÓN EDITABLE =====================
    private static final double ESCALA_MAX_HZ = 5000.0;   // máximo del eje vertical de la gráfica
    private static final int DEF_BANDA_MIN_HZ = 2000;     // banda de fuga por defecto
    private static final int DEF_BANDA_MAX_HZ = 4000;
    private static final int DEF_MUESTRAS = 5;            // muestras seguidas para confirmar

    // Contactos que salen en el reporte (REEMPLAZA los "COMPLETAR" con tus datos reales)
    private static final String[][] CONTACTOS = {
        {"Emergencias (México)", "911"},
        {"Responsable / Supervisor del área", "COMPLETAR: nombre y teléfono"},
        {"Mantenimiento", "COMPLETAR: nombre y teléfono"},
        {"Protección Civil / Bomberos local", "COMPLETAR: teléfono"}
    };
    // ==================================================================

    private static final Path ARCHIVO_EVENTOS =
            Paths.get(System.getProperty("user.home"), ".monitor_fugas", "eventos.csv");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    // Componentes de la interfaz
    private JLabel lblInfo;
    private JComboBox<String> cmbPuertos;
    private JButton btnRefrescar;
    private JButton btnConectar;
    private JButton btnSimular;
    private JButton btnForzarFuga;
    private JButton btnHistorialDemo;
    private JButton btnReporte;
    private JSpinner spnBandaMin;
    private JSpinner spnBandaMax;
    private JSpinner spnMuestras;
    private JLabel lblDeteccion;
    private JLabel lblEstado;
    private PanelOsciloscopio panelGrafica;

    // Datos de sesión
    private final String operador;
    private final String entorno;

    // Control de conexión y simulación
    private SerialPort puertoActivo = null;
    private volatile boolean conectado = false;
    private volatile boolean simulando = false;
    private Timer timerSimulacion;
    private int tickDemo = 0;
    private boolean forzarFugaDemo = false;
    private final Random rnd = new Random();

    // Detección de fugas (se usa solo desde el hilo de Swing)
    private final List<EventoFuga> historial = new ArrayList<>();
    private EventoFuga eventoActual = null;
    private int aciertos = 0;
    private int fallos = 0;
    private double ultimaFrecuencia = Double.NaN;

    // ---------------------------------------------------------------
    // Modelo de un evento de fuga
    // ---------------------------------------------------------------
    private static class EventoFuga {
        final LocalDateTime inicio;
        LocalDateTime fin;            // null mientras la fuga sigue activa
        double frecuenciaMediaHz;
        double sumaHz;
        int muestras;
        final boolean simulado;

        EventoFuga(LocalDateTime inicio, boolean simulado) {
            this.inicio = inicio;
            this.simulado = simulado;
        }

        long duracionSeg() {
            LocalDateTime f = (fin != null) ? fin : LocalDateTime.now();
            return Math.max(0, Duration.between(inicio, f).getSeconds());
        }
    }

    public VentanaGrafica(String operador, String entorno) {
        this.operador = operador;
        this.entorno = entorno;

        setTitle("Monitor de Fugas por Frecuencia - [" + entorno + "]");
        setSize(1120, 700);
        setMinimumSize(new Dimension(1020, 560));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 1. Barra superior: dos filas de controles
        JPanel panelSuperior = new JPanel(new GridLayout(2, 1));
        panelSuperior.setBackground(new Color(240, 240, 240));

        JPanel fila1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        fila1.setBackground(new Color(240, 240, 240));
        JPanel fila2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        fila2.setBackground(new Color(240, 240, 240));

        lblInfo = new JLabel("Operador: " + operador + " | Modo: " + entorno + " | Puerto:");
        lblInfo.setFont(new Font("Segoe UI", Font.BOLD, 12));

        cmbPuertos = new JComboBox<>();
        cmbPuertos.setPreferredSize(new Dimension(95, 26));

        btnRefrescar = new JButton("Refrescar");
        btnRefrescar.setFocusPainted(false);

        btnConectar = new JButton("Conectar Bluetooth");
        estilizarBoton(btnConectar, new Color(46, 125, 50));
        btnConectar.setPreferredSize(new Dimension(170, 28));

        btnSimular = new JButton("Probar Señal (Demo)");
        btnSimular.setFocusPainted(false);

        fila1.add(lblInfo);
        fila1.add(cmbPuertos);
        fila1.add(btnRefrescar);
        fila1.add(btnConectar);
        fila1.add(btnSimular);

        // Filtro de frecuencias
        spnBandaMin = new JSpinner(new SpinnerNumberModel(DEF_BANDA_MIN_HZ, 0, 100000, 50));
        spnBandaMax = new JSpinner(new SpinnerNumberModel(DEF_BANDA_MAX_HZ, 0, 100000, 50));
        spnMuestras = new JSpinner(new SpinnerNumberModel(DEF_MUESTRAS, 1, 100, 1));
        spnBandaMin.setPreferredSize(new Dimension(75, 26));
        spnBandaMax.setPreferredSize(new Dimension(75, 26));
        spnMuestras.setPreferredSize(new Dimension(55, 26));

        btnForzarFuga = new JButton("Forzar fuga (demo)");
        btnForzarFuga.setFocusPainted(false);

        btnHistorialDemo = new JButton("Historial demo 30 días");
        btnHistorialDemo.setFocusPainted(false);

        btnReporte = new JButton("Generar Reporte");
        estilizarBoton(btnReporte, new Color(25, 118, 210));
        btnReporte.setPreferredSize(new Dimension(140, 28));

        JLabel lblFiltro = new JLabel("Filtro de fuga (Hz):");
        lblFiltro.setFont(new Font("Segoe UI", Font.BOLD, 12));
        fila2.add(lblFiltro);
        fila2.add(new JLabel("mín"));
        fila2.add(spnBandaMin);
        fila2.add(new JLabel("máx"));
        fila2.add(spnBandaMax);
        fila2.add(new JLabel("muestras seguidas"));
        fila2.add(spnMuestras);
        fila2.add(btnForzarFuga);
        fila2.add(btnHistorialDemo);
        fila2.add(btnReporte);

        panelSuperior.add(fila1);
        panelSuperior.add(fila2);

        // 2. Centro: banner de estado + osciloscopio
        lblDeteccion = new JLabel("", SwingConstants.CENTER);
        lblDeteccion.setOpaque(true);
        lblDeteccion.setFont(new Font("Segoe UI", Font.BOLD, 20));
        lblDeteccion.setForeground(Color.WHITE);
        lblDeteccion.setPreferredSize(new Dimension(100, 44));

        panelGrafica = new PanelOsciloscopio();
        panelGrafica.setBanda(DEF_BANDA_MIN_HZ, DEF_BANDA_MAX_HZ);

        JPanel panelCentro = new JPanel(new BorderLayout());
        panelCentro.add(lblDeteccion, BorderLayout.NORTH);
        panelCentro.add(panelGrafica, BorderLayout.CENTER);

        // 3. Barra de estado inferior
        JPanel panelInferior = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panelInferior.setBackground(new Color(245, 245, 245));
        lblEstado = new JLabel("Puertos listos. Selecciona el tuyo o pulsa Probar Señal.");
        lblEstado.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblEstado.setForeground(new Color(80, 80, 80));
        panelInferior.add(lblEstado);

        add(panelSuperior, BorderLayout.NORTH);
        add(panelCentro, BorderLayout.CENTER);
        add(panelInferior, BorderLayout.SOUTH);

        // Listeners
        btnRefrescar.addActionListener(e -> cargarPuertosEnSegundoPlano());
        btnConectar.addActionListener(e -> alternarConexionBluetooth());
        btnSimular.addActionListener(e -> alternarSimulacion());
        btnForzarFuga.addActionListener(e -> alternarForzarFuga());
        btnHistorialDemo.addActionListener(e -> generarHistorialDemo());
        btnReporte.addActionListener(e -> generarReporte());
        spnBandaMin.addChangeListener(e -> actualizarBandaGrafica());
        spnBandaMax.addChangeListener(e -> actualizarBandaGrafica());

        cargarHistorialGuardado();
        actualizarBanner();
        cargarPuertosEnSegundoPlano();
    }

    private void estilizarBoton(JButton b, Color fondo) {
        b.setUI(new javax.swing.plaf.basic.BasicButtonUI()); // permite ver el color de fondo con el estilo de Windows
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setBackground(fondo);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
    }

    // ===================== PUERTOS Y CONEXIÓN =====================

    private void cargarPuertosEnSegundoPlano() {
        lblEstado.setText("Buscando puertos...");
        new Thread(() -> {
            try {
                SerialPort[] puertos = SerialPort.getCommPorts();
                SwingUtilities.invokeLater(() -> {
                    cmbPuertos.removeAllItems();
                    for (SerialPort p : puertos) {
                        cmbPuertos.addItem(p.getSystemPortName());
                    }
                    if (puertos.length == 0) {
                        lblEstado.setText("No se detectaron puertos COM. Conecta el dispositivo.");
                    } else {
                        lblEstado.setText("Puertos listos. Selecciona el tuyo o pulsa Probar Señal.");
                    }
                });
            } catch (Throwable ex) { // atrapa también UnsatisfiedLinkError (DLL nativa)
                SwingUtilities.invokeLater(() -> lblEstado.setText("Error al escanear puertos: " + ex));
            }
        }).start();
    }

    private void alternarConexionBluetooth() {
        if (!conectado) {
            String puertoSel = (String) cmbPuertos.getSelectedItem();
            if (puertoSel == null) {
                JOptionPane.showMessageDialog(this, "Selecciona un puerto COM de la lista.", "Aviso", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (simulando) alternarSimulacion();

            try {
                puertoActivo = SerialPort.getCommPort(puertoSel);
                puertoActivo.setBaudRate(9600);
                puertoActivo.setComPortTimeouts(SerialPort.TIMEOUT_SCANNER, 0, 0);

                if (puertoActivo.openPort()) {
                    reiniciarDetector();
                    conectado = true;
                    btnConectar.setText("Desconectar");
                    btnConectar.setBackground(new Color(180, 40, 40));
                    lblEstado.setText("Conectado a " + puertoSel);
                    panelGrafica.limpiarYMostrar("Recibiendo señal...");

                    new Thread(this::hiloRecepcionDatos).start();
                } else {
                    JOptionPane.showMessageDialog(this, "No se pudo abrir " + puertoSel + ".\nRevisa el emparejamiento Bluetooth en Windows.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Throwable ex) {
                JOptionPane.showMessageDialog(this, "Error con la librería serial:\n" + ex, "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            cerrarConexion();
            lblEstado.setText("Desconectado.");
            panelGrafica.limpiarYMostrar("Esperando señal por Bluetooth...");
        }
    }

    private void cerrarConexion() {
        conectado = false;
        if (puertoActivo != null && puertoActivo.isOpen()) {
            puertoActivo.closePort();
        }
        btnConectar.setText("Conectar Bluetooth");
        btnConectar.setBackground(new Color(46, 125, 50));
        reiniciarDetector();
    }

    private void hiloRecepcionDatos() {
        try (Scanner scanner = new Scanner(puertoActivo.getInputStream())) {
            while (conectado && scanner.hasNextLine()) {
                String linea = scanner.nextLine().trim();
                if (!linea.isEmpty()) {
                    try {
                        double frecuencia = Double.parseDouble(linea);
                        SwingUtilities.invokeLater(() -> procesarMuestra(frecuencia));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            if (conectado) {
                SwingUtilities.invokeLater(() -> {
                    cerrarConexion();
                    lblEstado.setText("Conexión perdida.");
                    panelGrafica.limpiarYMostrar("Conexión interrumpida.");
                });
            }
        }
    }

    // ===================== DETECCIÓN DE FUGAS =====================

    private double bandaMin() {
        double a = ((Number) spnBandaMin.getValue()).doubleValue();
        double b = ((Number) spnBandaMax.getValue()).doubleValue();
        return Math.min(a, b);
    }

    private double bandaMax() {
        double a = ((Number) spnBandaMin.getValue()).doubleValue();
        double b = ((Number) spnBandaMax.getValue()).doubleValue();
        return Math.max(a, b);
    }

    private void actualizarBandaGrafica() {
        panelGrafica.setBanda(bandaMin(), bandaMax());
    }

    /** Aquí entra cada frecuencia (Hz) que llega del sensor o de la simulación. */
    private void procesarMuestra(double frecuencia) {
        ultimaFrecuencia = frecuencia;
        panelGrafica.agregarPunto(frecuencia);

        boolean enBanda = frecuencia >= bandaMin() && frecuencia <= bandaMax();
        int n = ((Number) spnMuestras.getValue()).intValue();

        if (enBanda) {
            aciertos++;
            fallos = 0;
        } else {
            fallos++;
            aciertos = 0;
        }

        if (eventoActual == null && aciertos >= n) {
            iniciarFuga();
        } else if (eventoActual != null && fallos >= n) {
            terminarFuga();
        }

        if (eventoActual != null && enBanda) {
            eventoActual.sumaHz += frecuencia;
            eventoActual.muestras++;
        }

        actualizarBanner();
        if (!simulando) {
            lblEstado.setText(String.format(Locale.ROOT, "Frecuencia recibida: %.0f Hz", frecuencia));
        }
    }

    private void iniciarFuga() {
        eventoActual = new EventoFuga(LocalDateTime.now(), simulando);
        historial.add(eventoActual);
    }

    private void terminarFuga() {
        if (eventoActual == null) return;
        eventoActual.fin = LocalDateTime.now();
        if (eventoActual.muestras > 0) {
            eventoActual.frecuenciaMediaHz = eventoActual.sumaHz / eventoActual.muestras;
        }
        if (!eventoActual.simulado) {
            guardarEvento(eventoActual);
        }
        eventoActual = null;
    }

    private void reiniciarDetector() {
        if (eventoActual != null) terminarFuga();
        aciertos = 0;
        fallos = 0;
        ultimaFrecuencia = Double.NaN;
        actualizarBanner();
    }

    private void actualizarBanner() {
        if (eventoActual != null) {
            lblDeteccion.setText(String.format(Locale.ROOT,
                    "FUGA DETECTADA  |  %.0f Hz dentro de la banda %.0f - %.0f Hz",
                    ultimaFrecuencia, bandaMin(), bandaMax()));
            lblDeteccion.setBackground(new Color(198, 40, 40));
        } else if (Double.isNaN(ultimaFrecuencia)) {
            lblDeteccion.setText("SIN DATOS  |  esperando señal");
            lblDeteccion.setBackground(new Color(96, 110, 125));
        } else {
            lblDeteccion.setText(String.format(Locale.ROOT,
                    "SIN FUGA  |  frecuencia actual: %.0f Hz", ultimaFrecuencia));
            lblDeteccion.setBackground(new Color(46, 125, 50));
        }
    }

    // ===================== SIMULACIÓN =====================

    private void alternarSimulacion() {
        if (!simulando) {
            if (conectado) cerrarConexion();
            reiniciarDetector();
            simulando = true;
            tickDemo = 0;
            btnSimular.setText("Detener Demo");
            lblEstado.setText("Modo demostración activo (fuga automática cada ~20 s, o usa Forzar fuga)...");
            panelGrafica.limpiarYMostrar(null);

            timerSimulacion = new Timer(100, e -> {
                tickDemo++;
                boolean fugaAuto = (tickDemo % 200) >= 120; // 8 s de fuga cada 20 s
                boolean fuga = fugaAuto || forzarFugaDemo;
                double min = bandaMin();
                double max = bandaMax();
                double f;
                if (fuga) {
                    f = (min + max) / 2.0 + rnd.nextGaussian() * ((max - min) / 8.0);
                } else {
                    double base = Math.max(150, min * 0.4);
                    f = base + Math.sin(tickDemo * 0.1) * 80 + rnd.nextGaussian() * 30;
                }
                procesarMuestra(Math.max(0, f));
            });
            timerSimulacion.start();
        } else {
            if (timerSimulacion != null) timerSimulacion.stop();
            reiniciarDetector();
            simulando = false;
            forzarFugaDemo = false;
            btnForzarFuga.setText("Forzar fuga (demo)");
            btnSimular.setText("Probar Señal (Demo)");
            lblEstado.setText("Simulación detenida.");
            panelGrafica.limpiarYMostrar("Esperando señal por Bluetooth...");
        }
    }

    private void alternarForzarFuga() {
        if (!simulando) {
            JOptionPane.showMessageDialog(this, "Primero pulsa \"Probar Señal (Demo)\".", "Aviso", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        forzarFugaDemo = !forzarFugaDemo;
        btnForzarFuga.setText(forzarFugaDemo ? "Quitar fuga forzada" : "Forzar fuga (demo)");
    }

    /** Crea eventos de fuga ficticios de los últimos 30 días (no se guardan en disco). */
    private void generarHistorialDemo() {
        historial.removeIf(ev -> ev.simulado && ev != eventoActual);
        LocalDate hoy = LocalDate.now();
        LocalDateTime ahora = LocalDateTime.now();
        double min = bandaMin();
        double max = bandaMax();

        for (int d = 29; d >= 0; d--) {
            int r = rnd.nextInt(100);
            int n = r < 55 ? 0 : (r < 80 ? 1 : (r < 92 ? 2 : 3));
            for (int i = 0; i < n; i++) {
                LocalDateTime ini = hoy.minusDays(d).atTime(rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60));
                if (ini.isAfter(ahora)) continue;
                EventoFuga ev = new EventoFuga(ini, true);
                ev.fin = ini.plusSeconds(20 + rnd.nextInt(900));
                ev.frecuenciaMediaHz = min + (max - min) * (0.3 + 0.4 * rnd.nextDouble());
                historial.add(ev);
            }
        }
        historial.sort((a, b) -> a.inicio.compareTo(b.inicio));
        lblEstado.setText("Historial demo generado: " + eventosUltimos30Dias().size() + " eventos en 30 días. Ya puedes generar el reporte.");
    }

    // ===================== HISTORIAL EN DISCO =====================

    private void guardarEvento(EventoFuga ev) {
        try {
            Files.createDirectories(ARCHIVO_EVENTOS.getParent());
            String linea = ev.inicio + "," + ev.fin + "," +
                    String.format(Locale.ROOT, "%.1f", ev.frecuenciaMediaHz) + "\n";
            Files.writeString(ARCHIVO_EVENTOS, linea, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            lblEstado.setText("No se pudo guardar el evento: " + ex.getMessage());
        }
    }

    private void cargarHistorialGuardado() {
        if (!Files.exists(ARCHIVO_EVENTOS)) return;
        try {
            for (String linea : Files.readAllLines(ARCHIVO_EVENTOS, StandardCharsets.UTF_8)) {
                String[] p = linea.trim().split(",");
                if (p.length < 3) continue;
                try {
                    EventoFuga ev = new EventoFuga(LocalDateTime.parse(p[0]), false);
                    ev.fin = LocalDateTime.parse(p[1]);
                    ev.frecuenciaMediaHz = Double.parseDouble(p[2]);
                    historial.add(ev);
                } catch (Exception ignored) {}
            }
        } catch (IOException ex) {
            lblEstado.setText("No se pudo leer el historial: " + ex.getMessage());
        }
    }

    private List<EventoFuga> eventosUltimos30Dias() {
        LocalDateTime limite = LocalDateTime.now().minusDays(30);
        List<EventoFuga> r = new ArrayList<>();
        for (EventoFuga e : historial) {
            if (!e.inicio.isBefore(limite)) r.add(e);
        }
        return r;
    }

    // ===================== REPORTE =====================

    private void generarReporte() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Guardar reporte de fugas");
        fc.setSelectedFile(new File("reporte_fugas_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".html"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File destino = fc.getSelectedFile();
        if (!destino.getName().toLowerCase().endsWith(".html")) {
            destino = new File(destino.getParentFile(), destino.getName() + ".html");
        }

        try {
            Files.writeString(destino.toPath(), construirReporteHtml(), StandardCharsets.UTF_8);
            lblEstado.setText("Reporte guardado en: " + destino.getAbsolutePath());
            int r = JOptionPane.showConfirmDialog(this,
                    "Reporte guardado en:\n" + destino.getAbsolutePath() +
                    "\n\n¿Abrirlo ahora en el navegador? (desde ahí puedes imprimirlo o guardarlo como PDF)",
                    "Reporte generado", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (r == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(destino.toURI());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar el reporte:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String fmtDur(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private static final String CSS_REPORTE = """
        body{font-family:'Segoe UI',Arial,sans-serif;background:#f2f4f7;margin:0;color:#222}
        .wrap{max-width:900px;margin:0 auto;padding:24px}
        h1{margin:0 0 4px;font-size:26px}
        h2{margin:28px 0 10px;font-size:18px;border-bottom:2px solid #d0d7de;padding-bottom:4px}
        .meta{color:#555;font-size:13px;margin:0 0 16px}
        .aviso{background:#fff3cd;border:1px solid #e0c46c;padding:8px 12px;border-radius:6px;margin-bottom:14px;font-size:13px}
        .estado{border-radius:10px;padding:18px 22px;color:#fff}
        .estado.ok{background:#2e7d32}.estado.fuga{background:#c62828}
        .big{font-size:30px;font-weight:700;letter-spacing:1px}
        .estado p{margin:6px 0 0;font-size:14px}
        .cards{display:flex;gap:10px;flex-wrap:wrap}
        .card{flex:1;min-width:140px;background:#fff;border-radius:8px;padding:12px;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.12)}
        .card b{display:block;font-size:24px;margin-top:4px}
        .chart{display:flex;align-items:flex-end;gap:3px;height:150px;background:#fff;padding:10px 10px 4px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
        .col{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:100%;font-size:9px;color:#555}
        .bar{width:100%;background:#c62828;border-radius:2px 2px 0 0;min-height:2px}
        .bar.cero{background:#b0bec5}
        table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;font-size:13px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
        th{background:#37474f;color:#fff;text-align:left;padding:8px}
        td{padding:7px 8px;border-bottom:1px solid #e3e7eb}
        ul{background:#fff;border-radius:8px;padding:14px 14px 14px 34px;margin:0;box-shadow:0 1px 3px rgba(0,0,0,.12)}
        li{margin:5px 0;font-size:14px}
        .nota{font-size:12px;color:#666;margin-top:20px}
        @media print{body{background:#fff}.wrap{padding:0}}
        """;

    private String construirReporteHtml() {
        List<EventoFuga> ev = eventosUltimos30Dias();
        boolean fuga = eventoActual != null;
        LocalDateTime ahora = LocalDateTime.now();
        LocalDate hoy = ahora.toLocalDate();

        int[] porDia = new int[30]; // índice 29 = hoy, 0 = hace 29 días
        long segTotales = 0;
        int ultimos7 = 0;
        boolean hayDemo = false;
        for (EventoFuga e : ev) {
            long diasAtras = ChronoUnit.DAYS.between(e.inicio.toLocalDate(), hoy);
            if (diasAtras >= 0 && diasAtras < 30) porDia[(int) (29 - diasAtras)]++;
            if (diasAtras < 7) ultimos7++;
            segTotales += e.duracionSeg();
            if (e.simulado) hayDemo = true;
        }
        int total = ev.size();
        int maxDia = 1;
        for (int c : porDia) maxDia = Math.max(maxDia, c);

        String riesgo;
        String colorRiesgo;
        if (fuga || ultimos7 >= 3 || total >= 8) {
            riesgo = "ALTO";
            colorRiesgo = "#c62828";
        } else if (total >= 3) {
            riesgo = "MODERADO";
            colorRiesgo = "#ef6c00";
        } else {
            riesgo = "BAJO";
            colorRiesgo = "#2e7d32";
        }

        StringBuilder h = new StringBuilder(8192);
        h.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">")
         .append("<title>Reporte de fugas</title><style>").append(CSS_REPORTE).append("</style></head><body><div class=\"wrap\">");

        h.append("<h1>Reporte de monitoreo de fugas</h1>");
        h.append("<p class=\"meta\">Generado: ").append(ahora.format(FMT))
         .append(" &middot; Operador: ").append(esc(operador))
         .append(" &middot; Modo: ").append(esc(entorno)).append("</p>");
        if (hayDemo) {
            h.append("<div class=\"aviso\">Este reporte incluye datos <b>simulados</b> (modo demostración).</div>");
        }

        // ---- Estado actual ----
        h.append("<h2>1. Estado actual</h2>");
        h.append("<div class=\"estado ").append(fuga ? "fuga" : "ok").append("\">");
        h.append("<div class=\"big\">").append(fuga ? "FUGA DETECTADA" : "SIN FUGA").append("</div>");
        if (fuga) {
            h.append("<p>Inicio: ").append(eventoActual.inicio.format(FMT))
             .append(" &middot; Duración hasta ahora: ").append(fmtDur(eventoActual.duracionSeg()))
             .append(" &middot; Última frecuencia: ").append(String.format(Locale.ROOT, "%.0f Hz", ultimaFrecuencia)).append("</p>");
        } else if (Double.isNaN(ultimaFrecuencia)) {
            h.append("<p>No hay lecturas recientes del sensor.</p>");
        } else {
            h.append("<p>Última frecuencia leída: ").append(String.format(Locale.ROOT, "%.0f Hz", ultimaFrecuencia))
             .append(" (fuera de la banda de fuga).</p>");
        }
        h.append("<p>Filtro configurado: ").append(String.format(Locale.ROOT, "%.0f - %.0f Hz", bandaMin(), bandaMax()))
         .append(", confirmación con ").append(((Number) spnMuestras.getValue()).intValue()).append(" muestras seguidas.</p>");
        h.append("</div>");

        // ---- Resumen 30 días ----
        h.append("<h2>2. Frecuencia de fugas (últimos 30 días)</h2>");
        h.append("<div class=\"cards\">");
        h.append("<div class=\"card\">Eventos en 30 días<b>").append(total).append("</b></div>");
        h.append("<div class=\"card\">Últimos 7 días<b>").append(ultimos7).append("</b></div>");
        h.append("<div class=\"card\">Promedio por semana<b>")
         .append(String.format(Locale.ROOT, "%.1f", total / 30.0 * 7.0)).append("</b></div>");
        h.append("<div class=\"card\">Tiempo total con fuga<b style=\"font-size:18px\">").append(fmtDur(segTotales)).append("</b></div>");
        h.append("<div class=\"card\">Nivel de riesgo<b style=\"color:").append(colorRiesgo).append("\">").append(riesgo).append("</b></div>");
        h.append("</div>");

        h.append("<p class=\"meta\" style=\"margin-top:14px\">Eventos por día (izquierda: hace 29 días, derecha: hoy)</p>");
        h.append("<div class=\"chart\">");
        for (int i = 0; i < 30; i++) {
            int px = porDia[i] == 0 ? 2 : (int) Math.round(porDia[i] * 110.0 / maxDia);
            LocalDate dia = hoy.minusDays(29 - i);
            h.append("<div class=\"col\" title=\"").append(dia.format(DateTimeFormatter.ofPattern("dd/MM")))
             .append(": ").append(porDia[i]).append(" evento(s)\">")
             .append(porDia[i] > 0 ? porDia[i] : "")
             .append("<div class=\"bar").append(porDia[i] == 0 ? " cero" : "").append("\" style=\"height:").append(px).append("px\"></div>")
             .append(dia.getDayOfMonth()).append("</div>");
        }
        h.append("</div>");

        // ---- Tabla de eventos ----
        h.append("<h2>3. Detalle de eventos</h2>");
        if (ev.isEmpty()) {
            h.append("<p>No se registraron fugas en los últimos 30 días.</p>");
        } else {
            h.append("<table><tr><th>#</th><th>Inicio</th><th>Duración</th><th>Frecuencia media</th><th>Origen</th></tr>");
            int fila = 0;
            for (int i = ev.size() - 1; i >= 0 && fila < 50; i--, fila++) {
                EventoFuga e = ev.get(i);
                boolean activa = (e == eventoActual);
                double fm = activa && e.muestras > 0 ? e.sumaHz / e.muestras : e.frecuenciaMediaHz;
                h.append("<tr><td>").append(fila + 1).append("</td><td>").append(e.inicio.format(FMT))
                 .append("</td><td>").append(fmtDur(e.duracionSeg())).append(activa ? " (en curso)" : "")
                 .append("</td><td>").append(String.format(Locale.ROOT, "%.0f Hz", fm))
                 .append("</td><td>").append(e.simulado ? "Simulado" : "Sensor").append("</td></tr>");
            }
            h.append("</table>");
            if (ev.size() > 50) h.append("<p class=\"meta\">Se muestran los 50 eventos más recientes.</p>");
        }

        // ---- Recomendaciones ----
        h.append("<h2>4. Recomendaciones</h2><ul>");
        if (fuga) {
            h.append("<li><b>Avise de inmediato</b> al personal del área y al responsable.</li>")
             .append("<li>Cierre la válvula o el suministro del punto afectado <b>solo si puede hacerlo sin riesgo</b>.</li>")
             .append("<li>Evite fuentes de ignición (chispas, flamas, interruptores) si la fuga es de gas o de un líquido inflamable.</li>")
             .append("<li>Ventile y despeje el área; evacúe si hay olor fuerte, mareo o vapores.</li>")
             .append("<li>Contacte a mantenimiento y, si hay riesgo para las personas, a emergencias (ver contactos).</li>")
             .append("<li>No reanude la operación hasta que mantenimiento verifique y repare la fuga.</li>")
             .append("<li>Registre el evento: hora, zona afectada y acciones realizadas.</li>");
        } else {
            h.append("<li>No hay fuga en este momento: mantenga el monitoreo continuo.</li>")
             .append("<li>Verifique periódicamente la alimentación del sensor y la conexión Bluetooth.</li>")
             .append("<li>Valide el filtro de frecuencias con una fuga controlada para confirmar que la banda es correcta.</li>")
             .append("<li>Realice inspección visual y mantenimiento preventivo de uniones, válvulas y mangueras.</li>");
        }
        if (total >= 3) {
            h.append("<li><b>Se registraron ").append(total).append(" eventos en 30 días:</b> programe una inspección a fondo de la zona y revise los días con más incidencias.</li>");
        }
        if (ultimos7 >= 3) {
            h.append("<li><b>Hay una tendencia reciente:</b> ").append(ultimos7).append(" eventos en los últimos 7 días. Priorice la reparación.</li>");
        }
        h.append("</ul>");

        // ---- Contactos ----
        h.append("<h2>5. Contactos</h2><table><tr><th>Contacto</th><th>Teléfono / dato</th></tr>");
        for (String[] c : CONTACTOS) {
            h.append("<tr><td>").append(esc(c[0])).append("</td><td>").append(esc(c[1])).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<p class=\"nota\">Reporte generado automáticamente por el Monitor de Fugas. ")
         .append("Los datos provienen de las frecuencias enviadas por el microcontrolador y filtradas por la banda configurada.</p>");
        h.append("</div></body></html>");
        return h.toString();
    }

    // ===================== PANEL DEL OSCILOSCOPIO =====================

    private static class PanelOsciloscopio extends JPanel {
        private String mensajeCentral = "Esperando señal por Bluetooth...";
        private final List<Double> bufferPuntos = new ArrayList<>();
        private static final int MAX_PUNTOS = 400;
        private static final int MARGEN_IZQ = 56;
        private double bandaMin = 0;
        private double bandaMax = 0;

        public PanelOsciloscopio() {
            setBackground(new Color(18, 22, 30));
        }

        public void setBanda(double min, double max) {
            this.bandaMin = min;
            this.bandaMax = max;
            repaint();
        }

        public void limpiarYMostrar(String mensaje) {
            this.mensajeCentral = mensaje;
            this.bufferPuntos.clear();
            repaint();
        }

        public void agregarPunto(double valor) {
            mensajeCentral = null;
            bufferPuntos.add(valor);
            if (bufferPuntos.size() > MAX_PUNTOS) {
                bufferPuntos.remove(0);
            }
            repaint();
        }

        private int yDeFrecuencia(double f, int h) {
            double c = Math.min(Math.max(f, 0), ESCALA_MAX_HZ);
            return (int) ((h - 1) - (c / ESCALA_MAX_HZ) * (h - 1));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int ancho = w - MARGEN_IZQ;

            // Cuadrícula vertical
            g2.setColor(new Color(35, 55, 78, 110));
            for (int x = MARGEN_IZQ; x < w; x += 40) g2.drawLine(x, 0, x, h);

            // Cuadrícula horizontal cada 1000 Hz con etiquetas
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (int hz = 0; hz <= ESCALA_MAX_HZ; hz += 1000) {
                int y = yDeFrecuencia(hz, h);
                g2.setColor(new Color(35, 55, 78, 160));
                g2.drawLine(MARGEN_IZQ, y, w, y);
                g2.setColor(new Color(150, 170, 190));
                int ty = Math.min(Math.max(y + 4, 12), h - 2);
                g2.drawString(hz + " Hz", 4, ty);
            }

            // Banda de fuga
            if (bandaMax > bandaMin) {
                int yTop = yDeFrecuencia(bandaMax, h);
                int yBot = yDeFrecuencia(bandaMin, h);
                g2.setColor(new Color(255, 80, 80, 40));
                g2.fillRect(MARGEN_IZQ, yTop, ancho, Math.max(1, yBot - yTop));
                g2.setColor(new Color(255, 100, 100, 190));
                g2.drawLine(MARGEN_IZQ, yTop, w, yTop);
                g2.drawLine(MARGEN_IZQ, yBot, w, yBot);
                g2.drawString("Banda de fuga", MARGEN_IZQ + 8, yTop + 14);
            }

            if (mensajeCentral != null && bufferPuntos.isEmpty()) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 15));
                FontMetrics fm = g2.getFontMetrics();
                int x = MARGEN_IZQ + (ancho - fm.stringWidth(mensajeCentral)) / 2;
                int y = (h + fm.getAscent()) / 2 - 4;
                g2.drawString(mensajeCentral, x, y);
                return;
            }

            if (bufferPuntos.size() > 1) {
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double dx = (double) ancho / (MAX_PUNTOS - 1);
                for (int i = 0; i < bufferPuntos.size() - 1; i++) {
                    double f2 = bufferPuntos.get(i + 1);
                    boolean enBanda = f2 >= bandaMin && f2 <= bandaMax;
                    g2.setColor(enBanda ? new Color(255, 90, 90) : new Color(0, 255, 180));
                    int x1 = MARGEN_IZQ + (int) (i * dx);
                    int y1 = yDeFrecuencia(bufferPuntos.get(i), h);
                    int x2 = MARGEN_IZQ + (int) ((i + 1) * dx);
                    int y2 = yDeFrecuencia(f2, h);
                    g2.drawLine(x1, y1, x2, y2);
                }
            }
        }
    }

    // ===================== VENTANA DE LOGIN =====================

    private static class VentanaLogin extends JFrame {
        private final JTextField txtUsuario;
        private final JPasswordField txtPassword;
        private final JComboBox<String> cmbModo;
        private final Map<String, String> credenciales = new HashMap<>();

        public VentanaLogin() {
            // Usuarios válidos (Usuario -> Contraseña)
            credenciales.put("xim", "1234");
            credenciales.put("analista", "2026");

            setTitle("Acceso al Sistema");
            setSize(360, 260);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLocationRelativeTo(null);
            setResizable(false);
            setLayout(new GridBagLayout());

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 8, 6, 8);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            // Usuario
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("Usuario:"), gbc);
            gbc.gridx = 1;
            txtUsuario = new JTextField(14);
            add(txtUsuario, gbc);

            // Contraseña
            gbc.gridx = 0; gbc.gridy = 1;
            add(new JLabel("Contraseña:"), gbc);
            gbc.gridx = 1;
            txtPassword = new JPasswordField(14);
            add(txtPassword, gbc);

            // Selector de Entorno
            gbc.gridx = 0; gbc.gridy = 2;
            add(new JLabel("Modo:"), gbc);
            gbc.gridx = 1;
            String[] modos = {
                "Externa (Campo / Salida)",
                "Interna (Laboratorio / Pruebas)"
            };
            cmbModo = new JComboBox<>(modos);
            add(cmbModo, gbc);

            // Botón de Ingreso
            gbc.gridx = 0; gbc.gridy = 3;
            gbc.gridwidth = 2;
            JButton btnIngresar = new JButton("Iniciar Sesión");
            btnIngresar.setUI(new javax.swing.plaf.basic.BasicButtonUI());
            btnIngresar.setOpaque(true);
            btnIngresar.setBorderPainted(false);
            btnIngresar.setBackground(new Color(25, 118, 210));
            btnIngresar.setForeground(Color.WHITE);
            btnIngresar.setFont(new Font("Segoe UI", Font.BOLD, 12));
            btnIngresar.setFocusPainted(false);
            add(btnIngresar, gbc);

            btnIngresar.addActionListener(e -> validarAcceso());
            txtPassword.addActionListener(e -> validarAcceso());
        }

        private void validarAcceso() {
            String user = txtUsuario.getText().trim();
            String pass = new String(txtPassword.getPassword());
            String modo = (String) cmbModo.getSelectedItem();

            if (credenciales.containsKey(user) && credenciales.get(user).equals(pass)) {
                dispose(); // Cierra el login
                new VentanaGrafica(user, modo).setVisible(true); // Abre el monitor
            } else {
                JOptionPane.showMessageDialog(this,
                    "Usuario o contraseña incorrectos.\nPrueba con: xim (1234) o analista (2026)",
                    "Acceso Denegado",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            new VentanaLogin().setVisible(true);
        });
    }
}