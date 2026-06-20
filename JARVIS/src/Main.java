import org.vosk.Model;
import java.io.IOException;
import org.vosk.Recognizer;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.List;
import org.json.*;

public class Main {

    // ── Variáveis globais de controle de estado do assistente ──
    static Process processoFala        = null;
    static volatile boolean parar      = false;
    static volatile boolean iaAtiva    = false;
    static volatile boolean falando    = false;
    static volatile long    ultimaFala = 0;
    static final int TIMEOUT_MS        = 120_000;

    // ── Histórico de mensagens enviadas à IA (memória de conversa) ──
    static List<JSONObject> historico  = new ArrayList<JSONObject>();
    static final int MAX_HISTORICO     = 10;

    // ── Palavras que ativam APENAS comandos rápidos (abrir apps, etc.) ──
    static final List<String> PALAVRAS_COMANDO = new ArrayList<String>(Arrays.asList(
        "jarvis", "jardim", "jardins", "james", "jã¡", "jazz", "já", "jair", "jaz", "abrir"
    ));

    // ── Palavra EXCLUSIVA para ativar o modo de conversa com a IA ──
    // Diga "ativa nexus" para conversar com a IA
    static final List<String> PALAVRAS_IA = new ArrayList<String>(Arrays.asList(
    	    "modo nexus", "modo nexo", "modo anexos"
    	));

    // ── Método responsável por sintetizar e reproduzir fala via PowerShell com a voz Maria ──
    public static void falar(String texto) throws Exception {
        parar   = false;
        falando = true;
        texto   = texto.replace("'", "");

        String script =
            "Add-Type -AssemblyName System.Speech; " +
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "$s.SelectVoice('Microsoft Antonio Online'); " +
            "$s.Speak('" + texto + "');";

        processoFala = new ProcessBuilder("powershell", "-Command", script).start();

        while (processoFala.isAlive()) {
            if (parar) { processoFala.destroyForcibly(); break; }
            Thread.sleep(100);
        }

        falando = false;
    }

    // ── Interrompe imediatamente qualquer fala em andamento ──
    public static void pararFala() {
        parar   = true;
        falando = false;
        if (processoFala != null && processoFala.isAlive()) {
            processoFala.destroyForcibly();
            System.out.println("Fala interrompida.");
        }
    }

    // ── Encerra todos os processos do Ollama ──
    public static void fecharOllama() {
        try {
            String[] processos = {"ollama.exe", "ollama_llama_server.exe"};
            for (String processo : processos) {
                new ProcessBuilder("taskkill", "/F", "/IM", processo, "/T").start();
            }
            Thread.sleep(1000);
            System.out.println("Ollama fechado.");
        } catch (Exception e) {
            System.out.println("Erro ao fechar Ollama: " + e.getMessage());
        }
    }

    // ── Envia a pergunta do usuário à IA local (Ollama) com histórico de conversa e retorna a resposta ──
    public static String perguntarIA(String pergunta) throws Exception {

        JSONObject msgUsuario = new JSONObject();
        msgUsuario.put("role", "user");
        msgUsuario.put("content", pergunta);
        historico.add(msgUsuario);

        while (historico.size() > MAX_HISTORICO * 2) {
            historico.remove(0);
        }

        JSONObject payload = new JSONObject();
        payload.put("model", "tinyllama");
        payload.put("stream", false);

        JSONObject options = new JSONObject();
        options.put("num_predict", 30);
        options.put("temperature", 0.7);
        payload.put("options", options);

        JSONArray msgs = new JSONArray();

        JSONObject sistema = new JSONObject();
        sistema.put("role", "system");
        sistema.put("content",
            "Você é o Jarvis, assistente pessoal do usuário. " +
            "Responda SEMPRE em no MÁXIMO 1 frase curta em português brasileiro. Máximo 15 palavras. " +
            "NUNCA invente leis, fundações ou informações falsas. " +
            "Seja carismatico, no sentido de ser engraçado. Se não souber algo, diga que não sabe." +
            "O nome do usuário é Gustavo."
        );
        msgs.put(sistema);

        for (JSONObject m : historico) msgs.put(m);
        payload.put("messages", msgs);

        URL url = new URL("http://localhost:11434/api/chat");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(90000);

        try (OutputStream os = con.getOutputStream()) {
            os.write(payload.toString().getBytes("utf-8"));
        }

        BufferedReader br = new BufferedReader(
            new InputStreamReader(con.getInputStream(), "utf-8"));
        StringBuilder sb = new StringBuilder();
        String linha;
        while ((linha = br.readLine()) != null) sb.append(linha);

        JSONObject resposta = new JSONObject(sb.toString());
        String textoResposta = resposta
            .getJSONObject("message")
            .getString("content")
            .replace("\\n", " ")
            .trim();

        JSONObject msgJarvis = new JSONObject();
        msgJarvis.put("role", "assistant");
        msgJarvis.put("content", textoResposta);
        historico.add(msgJarvis);

        return textoResposta;
    }

    // ── Ativa o modo de conversa com a IA ──
    static void ativarIA() throws Exception {
        if (!iaAtiva) {
            iaAtiva = true;
            ultimaFala = System.currentTimeMillis();

            // ── Inicia o Ollama ──
         // ── Verifica se Ollama já está rodando, se não, inicia ──
            try {
                HttpURLConnection teste = (HttpURLConnection) 
                    new URL("http://localhost:11434").openConnection();
                teste.setConnectTimeout(1000);
                teste.connect();
                System.out.println("[Jarvis] Ollama já estava rodando.");
            } catch (Exception e) {
                ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "set OLLAMA_VULKAN=1 && \"C:\\Users\\gh880\\AppData\\Local\\Programs\\Ollama\\ollama.exe\" serve"
                );
                pb.redirectErrorStream(true);
                pb.start();
                System.out.println("[Jarvis] Ollama iniciado.");
                Thread.sleep(5000);
            }
            System.out.println("[Jarvis] IA ativada!");
            falar("Sim, pode falar.");
        }
    }

    // ── Desativa o modo de conversa com a IA ──
    static void desativarIA() throws Exception {
        if (iaAtiva) {
            iaAtiva = false;
            pararFala();
            fecharOllama();
            System.out.println("[Jarvis] IA desativada.");
        }
    }

    // ── Tenta abrir um app/site pelo comando de voz. Retorna true se executou algo. ──
    static boolean tentarAbrirApp(String cmd) throws Exception {
        if (cmd.contains("youtube") || cmd.contains("outubro")) {
            falar("O dopamina barata do caralho em...");
            new ProcessBuilder("cmd", "/c", "start", "https://youtube.com").start(); return true;
        }
        if (cmd.contains("roleta")) {
            falar("Isso de novo.");
            new ProcessBuilder("cmd", "/c", "start", "https://spinthewheel.io/pt").start(); return true;
        }
        if (cmd.contains("temperatura")) {
            falar("Tá preocupado com o que, senhor.");
            new ProcessBuilder("explorer.exe",
                "shell:AppsFolder\\9426MICRO-STARINTERNATION.MSICenter_kzh8wxbdkxb8p!App").start(); return true;
        }
        if (cmd.contains("google")) {
            falar("ta me trocando né vagabundo");
            new ProcessBuilder("cmd", "/c", "start", "https://google.com").start(); return true;
        }
        if (cmd.contains("claude") || cmd.contains("claudio")) {
            falar("ta me trocando né vagabundo");
            new ProcessBuilder("cmd", "/c", "start", "https://claude.ai/").start(); return true;
        }
        if (cmd.contains("calculadora")) {
            falar("Que saco em...");
            new ProcessBuilder("cmd", "/c", "start", "calc").start(); return true;
        }
        if (cmd.contains("programar") || cmd.contains("programa")) {
            falar("bora codar!");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:\\Users\\gh880\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe'").start();
            return true;
        }
        if (cmd.contains("java")) {
            falar("bora codar!");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:\\Users\\gh880\\eclipse\\java-2025-09\\eclipse\\eclipse.exe'").start();
            return true;
        }
        if (cmd.contains("hora de jogar")) {

            falar("Vai jogar o que senhor?");

            try {

                // Abre a Steam com parâmetros mais estáveis
                ProcessBuilder steam = new ProcessBuilder(
                        "C:\\Program Files (x86)\\Steam\\steam.exe",
                        "-no-browser",
                        "-nofriendsui"
                );

                steam.start();

                // Espera a Steam iniciar completamente
                Thread.sleep(10000);

                // Abre o Big Picture
                ProcessBuilder bigPicture = new ProcessBuilder(
                        "cmd",
                        "/c",
                        "start",
                        "",
                        "steam://open/bigpicture"
                );

                bigPicture.start();

            } catch (Exception e) {

                e.printStackTrace();
                falar("Erro ao abrir a Steam senhor.");
            }
            
            falar("Também vou abrir o spotify que eu sei que você gosta");

            new ProcessBuilder("cmd", "/c", "start", "spotify").start();
            return true;
        }
        if (cmd.contains("esportes") || cmd.contains("esporte") || cmd.contains("pai") || cmd.contains("se pode")) {
            falar("Vai ouvir uma musiquinha?");
            new ProcessBuilder("cmd", "/c", "start", "spotify").start(); return true;
        }
        if (cmd.contains("valor") || cmd.contains("valores") || cmd.contains("valorante")) {
            falar("Esse jogo de novo... Para com essa merda");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:/Riot Games/Riot Client/RiotClientServices.exe'").start();
            
            falar("Também vou abrir o spotify que eu sei que você gosta");
            
            new ProcessBuilder("cmd", "/c", "start", "spotify").start();
            return true;
            
        }
        if (cmd.contains("discord") || cmd.contains("discorde") || cmd.contains("escolha") || cmd.contains("discã³rdia")) {
            falar("Vai falar com os manos!!");
            new ProcessBuilder("C:/Users/gh880/AppData/Local/Discord/Update.exe",
                "--processStart", "Discord.exe").start(); return true;
        }
        return false;
    }

    // ── Verifica e executa comandos rápidos (silenciar, sistema, fechar tudo) ──
    static boolean tentarComando(String cmd) throws Exception {

        // ── Silenciar / encerrar IA ──
        if (cmd.contains("para") || cmd.contains("cala") || cmd.contains("silencio") ||
            cmd.contains("dormir") || cmd.contains("pode ir") ||
            cmd.contains("valeu") || cmd.contains("obrigado")) {
            pararFala();
            iaAtiva = false;
            fecharOllama();
            return true;
        }

        // ── Comandos de sistema ──
        if (cmd.contains("desligar computador") || cmd.contains("ligar computador")) {
            falar("Até a proxima, desligando em 30 segundos.");
            new ProcessBuilder("cmd", "/c", "shutdown /s /t 30").start(); return true;
        }
        if (cmd.contains("reinicia computador") || cmd.contains("inicia computador")) {
            falar("Reiniciando computador em 30 segundos.");
            new ProcessBuilder("cmd", "/c", "shutdown /r /t 30").start(); return true;
        }
        if (cmd.contains("cancelar") || cmd.contains("cancela")) {
            falar("Chato.");
            new ProcessBuilder("cmd", "/c", "shutdown /a").start(); return true;
        }

        // ── Fechar tudo ──
        if (cmd.contains("fechar tudo") || cmd.contains("deixar tudo") || cmd.contains("bagunã§a")) {
            falar("Caramba faço tudo nessa casa.");
            String[] appsParaFechar = {
                "EALauncher.exe", "EALauncherHelper.exe", "upc.exe",
                "UplayWebCore.exe", "EACefSubProcess.exe", "EABackgroundService.exe",
                "EADesktop.exe", "steam.exe", "steamwebhelper.exe",
                "TranslucentTB.exe", "brave.exe", "wallpaper64.exe",
                "sqlceip.exe", "sqlservr.exe"
            };
            for (String app : appsParaFechar) {
                try { new ProcessBuilder("taskkill", "/F", "/IM", app, "/T").start(); }
                catch (Exception ignored) {}
            }
            return true;
        }

        return false;
    }

    // ── Método central que interpreta o texto reconhecido e decide a ação a tomar ──
    public static void processar(String texto) {

        // ── Bloqueia entradas enquanto está falando, exceto silêncio ──
        if (falando) {
            if (texto.contains("cala") || texto.contains("para") || texto.contains("silencio")) {
                pararFala();
                iaAtiva = false;
            }
            return;
        }

        System.out.println("Reconhecido: " + texto);

        // ── Verifica se é o comando de ativação da IA ──
        boolean ativarModoIA = false;
        for (String p : PALAVRAS_IA) {
            if (texto.contains(p)) { ativarModoIA = true; break; }
        }

        // ── Verifica se é um comando de app (contém "jarvis" ou variações) ──
        boolean temPalavraComando = false;
        for (String p : PALAVRAS_COMANDO) {
            if (texto.contains(p)) { temPalavraComando = true; break; }
        }

        // ── Remove palavras-chave para isolar o comando real ──
        String cmd = texto;
        for (String p : PALAVRAS_IA) cmd = cmd.replace(p, "").trim();
        for (String p : PALAVRAS_COMANDO) cmd = cmd.replace(p, "").trim();

        // ────────────────────────────────────────────────────────────────
        // CASO 1 — "modo nexus" dito → ativa conversa com IA
        // ────────────────────────────────────────────────────────────────
        if (ativarModoIA) {
            try {
                if (!iaAtiva) {
                    ativarIA();
                } else {
                    // Já está ativa: processa o resto como pergunta à IA
                    ultimaFala = System.currentTimeMillis();
                    if (!cmd.isEmpty()) {
                        boolean foiComando = tentarComando(cmd);
                        if (!foiComando) {
                            final String pergunta = cmd;
                            new Thread(() -> {
                                try {
                                    ultimaFala = System.currentTimeMillis();
                                    String resposta = perguntarIA(pergunta);
                                    System.out.println("IA: " + resposta);
                                    if (!parar && iaAtiva) falar(resposta);
                                } catch (Exception e) {
                                    System.out.println("Erro IA: " + e.getMessage());
                                }
                            }).start();
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
            return;
        }

        // ────────────────────────────────────────────────────────────────
        // CASO 2 — "jarvis abre X" → executa app direto, sem precisar de IA ativa
        // ────────────────────────────────────────────────────────────────
        if (temPalavraComando) {
            try {
                boolean abriu = tentarAbrirApp(cmd);
                if (!abriu) tentarComando(cmd);
            } catch (Exception e) { e.printStackTrace(); }
            return;
        }

        // ────────────────────────────────────────────────────────────────
        // CASO 3 — IA está ativa → trata como conversa/pergunta
        // ────────────────────────────────────────────────────────────────
        if (iaAtiva) {
            ultimaFala = System.currentTimeMillis();
            if (cmd.isEmpty() && texto.isEmpty()) return;

            final String entrada = texto;
            try {
                boolean foiComando = tentarComando(entrada);
                if (!foiComando) {
                    new Thread(() -> {
                        try {
                            ultimaFala = System.currentTimeMillis();
                            if (!iaAtiva) return;
                            String resposta = perguntarIA(entrada);
                            System.out.println("IA: " + resposta);
                            if (!parar && iaAtiva) falar(resposta);
                        } catch (Exception e) {
                            System.out.println("Erro IA: " + e.getMessage());
                        }
                    }).start();
                }
            } catch (Exception e) { e.printStackTrace(); }
            return;
        }

        // ────────────────────────────────────────────────────────────────
        // CASO 4 — Nenhuma palavra-chave, IA inativa → ignora ou tenta comando global
        // ────────────────────────────────────────────────────────────────
        try { tentarComando(texto); } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Ponto de entrada do programa ──
    public static void main(String[] args) throws Exception {

        if (!SystemTray.isSupported()) {
            System.out.println("SystemTray não suportado.");
            return;
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.CYAN); g.fillOval(0, 0, 16, 16); g.dispose();

        PopupMenu menu = new PopupMenu();
        MenuItem sair = new MenuItem("Sair");
        sair.addActionListener(e -> System.exit(0));
        menu.add(sair);

        final TrayIcon trayIcon = new TrayIcon(image, "Jarvis", menu);
        trayIcon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(trayIcon);

        trayIcon.addActionListener(e -> {
            try {
                if (iaAtiva) desativarIA(); else ativarIA();
            } catch (Exception ex) { System.out.println("Erro tray: " + ex.getMessage()); }
        });

        // ── Thread de timeout da IA ──
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                        if (iaAtiva && System.currentTimeMillis() - ultimaFala > TIMEOUT_MS) {
                            System.out.println("[Jarvis] Timeout — desativando IA.");
                            try { desativarIA(); } catch (Exception ex) { ex.printStackTrace(); }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }).start();

        falar("Jarvis iniciado.");
        trayIcon.displayMessage("Jarvis",
            "Diga 'Jarvis abre X' para apps | 'Ativa Nexus' para conversar com a IA",
            TrayIcon.MessageType.INFO);

        Model model = new Model("C:/programacao/JARVIS/JARVIS/model/vosk-model-small-pt-0.3");
        Recognizer rec = new Recognizer(model, 16000);

        TargetDataLine mic = AudioSystem.getTargetDataLine(
            new AudioFormat(16000, 16, 1, true, false));
        mic.open();
        mic.start();

        byte[] buffer = new byte[4096];

        while (true) {
            int bytesRead = mic.read(buffer, 0, buffer.length);

            if (rec.acceptWaveForm(buffer, bytesRead)) {
                String resultado = rec.getResult();
                JSONObject jsonVosk = new JSONObject(resultado);
                String texto = jsonVosk.optString("text", "").trim().toLowerCase();

                if (!texto.isEmpty()) {
                    processar(texto);
                }
            }

            Thread.sleep(20);
        }
    }
}