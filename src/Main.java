import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.*;
import javazoom.jl.player.Player;
import org.json.*;
import org.vosk.Model;
import org.vosk.Recognizer;

public class Main {

    interface Acao {
        void executar(String cmd) throws Exception;
    }

    static class Comando {
        final String[] chaves;
        final Acao acao;

        Comando(Acao acao, String... chaves) {
            this.acao = acao;
            this.chaves = chaves;
        }

        boolean combina(String cmd) {
            for (String chave : chaves) {
                if (cmd.contains(chave)) return true;
            }
            return false;
        }
    }

    static Process processoFala        = null;
    static volatile Player playerAtual = null;
    static volatile boolean parar      = false;
    static volatile boolean falando    = false;

    static final Object TRAVA_FALA        = new Object();
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    static volatile Acao acaoPendente = null;
    static volatile long pendenteAte  = 0;

    static final String PYTHON       = "C:/Users/gh880/AppData/Local/Programs/Python/Python313/python.exe";
    static final String EDGE_VOZ     = "pt-BR-AntonioNeural";
    static final String EDGE_TOM     = "-10Hz";
    static final String EDGE_CACHE   = "C:/programacao/JARVIS/JARVIS/edge_cache";

    static final String CLIMA_NOME   = "São Paulo";
    static final String CLIMA_LAT    = "-23.55";
    static final String CLIMA_LON    = "-46.63";

    static final String[] PALAVRAS_COMANDO = {
        "jarvis", "jardim", "jardins", "james", "jã¡", "jazz", "já", "jair", "jaz", "abrir", "chaves", "jardas", "jales", "jarvis", "jades"
    };

    static final Map<String, Integer> NUMEROS = new HashMap<String, Integer>();

    static {
        NUMEROS.put("um", 1);
        NUMEROS.put("uma", 1);
        NUMEROS.put("dois", 2);
        NUMEROS.put("duas", 2);
        NUMEROS.put("três", 3);
        NUMEROS.put("tres", 3);
        NUMEROS.put("quatro", 4);
        NUMEROS.put("cinco", 5);
        NUMEROS.put("seis", 6);
        NUMEROS.put("sete", 7);
        NUMEROS.put("oito", 8);
        NUMEROS.put("nove", 9);
        NUMEROS.put("dez", 10);
        NUMEROS.put("onze", 11);
        NUMEROS.put("doze", 12);
        NUMEROS.put("treze", 13);
        NUMEROS.put("catorze", 14);
        NUMEROS.put("quatorze", 14);
        NUMEROS.put("quinze", 15);
        NUMEROS.put("dezesseis", 16);
        NUMEROS.put("dezessete", 17);
        NUMEROS.put("dezoito", 18);
        NUMEROS.put("dezenove", 19);
        NUMEROS.put("vinte", 20);
        NUMEROS.put("trinta", 30);
        NUMEROS.put("quarenta", 40);
        NUMEROS.put("cinquenta", 50);
        NUMEROS.put("sessenta", 60);
    }

    static void tocar(File mp3) throws Exception {
        FileInputStream fis = new FileInputStream(mp3);
        try {
            Player player = new Player(fis);
            playerAtual = player;
            player.play();
        } finally {
            playerAtual = null;
            fis.close();
        }
    }

    public static void falar(String texto) throws Exception {
        JarvisGUI.registrarJarvis(texto);
        synchronized (TRAVA_FALA) {
            falarInterno(texto);
        }
    }

    static void falarInterno(String texto) throws Exception {
        parar   = false;
        falando = true;

        try {
            texto = texto.replace("\r", " ").replace("\n", " ").trim();

            if (texto.isEmpty()) return;

            File pasta = new File(EDGE_CACHE);
            pasta.mkdirs();

            String chave = EDGE_VOZ + EDGE_TOM + "|" + texto;
            String id = UUID.nameUUIDFromBytes(chave.getBytes("utf-8")).toString();
            File mp3 = new File(pasta, id + ".mp3");

            if (!mp3.exists()) {
                File txt = new File(pasta, id + ".txt");
                File tmp = new File(pasta, id + ".tmp");
                File log = new File(pasta, "erro.log");

                java.nio.file.Files.write(txt.toPath(), texto.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                ProcessBuilder gerador = new ProcessBuilder(
                    PYTHON, "-m", "edge_tts",
                    "--voice", EDGE_VOZ,
                    "--pitch=" + EDGE_TOM,
                    "--file", txt.getAbsolutePath(),
                    "--write-media", tmp.getAbsolutePath());
                gerador.redirectOutput(ProcessBuilder.Redirect.to(new File("NUL")));
                gerador.redirectError(ProcessBuilder.Redirect.to(log));

                long inicio = System.currentTimeMillis();
                processoFala = gerador.start();

                while (processoFala.isAlive()) {
                    if (parar || System.currentTimeMillis() - inicio > 15000) {
                        processoFala.destroyForcibly();
                        break;
                    }
                    Thread.sleep(50);
                }

                boolean ok = !parar && !processoFala.isAlive() && processoFala.exitValue() == 0
                             && tmp.exists() && tmp.length() > 0;

                if (ok) {
                    tmp.renameTo(mp3);
                } else {
                    tmp.delete();
                    if (!parar) {
                        System.out.println("Falha ao gerar a voz. Detalhes do erro:");
                        if (log.exists()) {
                            System.out.println(new String(java.nio.file.Files.readAllBytes(log.toPath()), "utf-8"));
                        }
                    }
                }

                txt.delete();
            }

            if (!parar && mp3.exists()) {
                tocar(mp3);
            }
        } finally {
            falando = false;
        }
    }

    public static void pararFala() {
        parar   = true;
        falando = false;
        if (processoFala != null && processoFala.isAlive()) {
            processoFala.destroyForcibly();
        }
        if (playerAtual != null) {
            playerAtual.close();
        }
        System.out.println("Fala interrompida.");
    }

    static boolean temPalavra(String texto, String... palavras) {
        for (String token : texto.trim().split("\\s+")) {
            for (String p : palavras) {
                if (token.equals(p)) return true;
            }
        }
        return false;
    }

    static String semPalavrasComando(String texto) {
        StringBuilder sb = new StringBuilder();
        for (String token : texto.trim().split("\\s+")) {
            boolean ehComando = false;
            for (String p : PALAVRAS_COMANDO) {
                if (token.equals(p)) { ehComando = true; break; }
            }
            if (!ehComando) sb.append(token).append(" ");
        }
        return sb.toString().trim();
    }

    static void falarLista(String... partes) throws Exception {
        for (int i = 0; i < partes.length; i++) {
            if (i > 0 && parar) return;
            falar(partes[i]);
        }
    }

    static void pedirConfirmacao(String pergunta, Acao acao) throws Exception {
        falar(pergunta);
        acaoPendente = acao;
        pendenteAte  = System.currentTimeMillis() + 10000;
    }

    static void abrirUrl(String url) throws Exception {
        new ProcessBuilder("cmd", "/c", "start", url).start();
    }

    static String textoHora() {
        LocalTime agora = LocalTime.now();
        int h = agora.getHour();
        int m = agora.getMinute();

        String base;
        if (h == 0) base = "É meia-noite";
        else if (h == 12) base = "É meio-dia";
        else if (h == 1) base = "É uma hora";
        else base = "São " + h + " horas";

        if (m == 0) return base + " em ponto.";
        if (m == 1) return base + " e um minuto.";
        return base + " e " + m + " minutos.";
    }

    static String textoData() {
        LocalDate hoje = LocalDate.now();
        Locale pt = new Locale("pt", "BR");
        String diaSemana = hoje.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, pt);
        String mes = hoje.getMonth().getDisplayName(java.time.format.TextStyle.FULL, pt);
        return "Hoje é " + diaSemana + ", " + hoje.getDayOfMonth() + " de " + mes + " de " + hoje.getYear() + ".";
    }

    static void falarDataHora(boolean pedeData, boolean pedeHora) throws Exception {
        String fala = "";
        if (pedeData) fala += textoData() + " ";
        if (pedeHora) fala += textoHora();
        falar(fala.trim());
    }

    static Integer buscarTemperatura(JSONObject no, String chave, String hardware, String grupo) {
        String texto = no.optString("Text", "");
        JSONArray filhos = no.optJSONArray("Children");

        if (filhos == null || filhos.length() == 0) {
            if (grupo.equals("Temperatures") && hardware.toLowerCase().contains(chave)) {
                Matcher m = Pattern.compile("\\d+([.,]\\d+)?").matcher(no.optString("Value", ""));
                if (m.find()) {
                    double valor = Double.parseDouble(m.group().replace(',', '.'));
                    return (int) Math.round(valor);
                }
            }
            return null;
        }

        String novoHardware = hardware;
        String novoGrupo = "";
        if (texto.toLowerCase().contains(chave)) novoHardware = texto;
        if (texto.equals("Temperatures")) novoGrupo = "Temperatures";

        for (int i = 0; i < filhos.length(); i++) {
            Integer r = buscarTemperatura(filhos.getJSONObject(i), chave, novoHardware, novoGrupo);
            if (r != null) return r;
        }
        return null;
    }

    static void falarTemperaturas() throws Exception {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:8085/data.json").openConnection();
            con.setConnectTimeout(2000);
            con.setReadTimeout(2000);

            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"));
            StringBuilder sb = new StringBuilder();
            String linha;
            while ((linha = br.readLine()) != null) sb.append(linha);
            br.close();

            JSONObject raiz = new JSONObject(sb.toString());
            Integer cpu = buscarTemperatura(raiz, "ryzen", "", "");
            Integer gpu = buscarTemperatura(raiz, "radeon", "", "");

            if (cpu == null && gpu == null) {
                falar("Não encontrei os sensores de temperatura, senhor.");
                return;
            }

            String fala = "";
            if (cpu != null) fala += "A CPU está a " + cpu + " graus. ";
            else fala += "Não achei a temperatura da CPU. ";
            if (gpu != null) fala += "A GPU está a " + gpu + " graus.";
            else fala += "Não achei a temperatura da GPU.";

            falar(fala);
        } catch (IOException e) {
            falar("Não consegui ler as temperaturas, senhor. O Libre Hardware Monitor está aberto?");
        }
    }

    static Object lerMetricaSistema(Object os, String metodo) throws Exception {
        Class<?> tipo = Class.forName("com.sun.management.OperatingSystemMXBean");
        return tipo.getMethod(metodo).invoke(os);
    }

    static void falarDesempenho() throws Exception {
        String fala;
        try {
            Object os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            lerMetricaSistema(os, "getSystemCpuLoad");
            Thread.sleep(1000);
            double cpu = ((Number) lerMetricaSistema(os, "getSystemCpuLoad")).doubleValue();
            long total = ((Number) lerMetricaSistema(os, "getTotalPhysicalMemorySize")).longValue();
            long livre = ((Number) lerMetricaSistema(os, "getFreePhysicalMemorySize")).longValue();
            long ram = Math.round((total - livre) * 100.0 / total);

            if (cpu < 0) fala = "Não consegui medir a CPU, senhor. A memória está em " + ram + " por cento.";
            else fala = "A CPU está em " + Math.round(cpu * 100) + " por cento e a memória em " + ram + " por cento.";
        } catch (Exception e) {
            fala = "Não consegui medir o desempenho, senhor.";
        }
        falar(fala);
    }

    static String descricaoClima(int codigo) {
        if (codigo == 0) return "céu limpo";
        if (codigo <= 2) return "com poucas nuvens";
        if (codigo == 3) return "nublado";
        if (codigo == 45 || codigo == 48) return "com neblina";
        if (codigo >= 51 && codigo <= 57) return "com garoa";
        if (codigo >= 61 && codigo <= 67) return "com chuva";
        if (codigo >= 71 && codigo <= 77) return "com neve";
        if (codigo >= 80 && codigo <= 82) return "com pancadas de chuva";
        if (codigo >= 85 && codigo <= 86) return "com neve";
        if (codigo >= 95) return "com tempestade";
        return "condição desconhecida";
    }

    static void falarClima() throws Exception {
        String fala;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + CLIMA_LAT
                + "&longitude=" + CLIMA_LON + "&current=temperature_2m,weather_code&timezone=auto");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"));
            StringBuilder sb = new StringBuilder();
            String linha;
            while ((linha = br.readLine()) != null) sb.append(linha);
            br.close();

            JSONObject atual = new JSONObject(sb.toString()).getJSONObject("current");
            long temp = Math.round(atual.getDouble("temperature_2m"));
            int codigo = atual.getInt("weather_code");

            fala = "Em " + CLIMA_NOME + " faz " + temp + " graus, " + descricaoClima(codigo) + ".";
        } catch (Exception e) {
            fala = "Não consegui consultar o clima, senhor.";
        }
        falar(fala);
    }

    static void teclaMidia(int tecla, int vezes) throws Exception {
        String script =
            "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; "
            + "public class TeclaMidia { [DllImport(\"user32.dll\")] "
            + "public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra); }'; "
            + "1.." + vezes + " | ForEach-Object { "
            + "[TeclaMidia]::keybd_event(" + tecla + ", 0, 1, [UIntPtr]::Zero); "
            + "[TeclaMidia]::keybd_event(" + tecla + ", 0, 3, [UIntPtr]::Zero); "
            + "Start-Sleep -Milliseconds 30 }";
        String codificado = java.util.Base64.getEncoder().encodeToString(script.getBytes("UTF-16LE"));
        new ProcessBuilder("powershell", "-NoProfile", "-EncodedCommand", codificado).start();
    }

    static void pesquisar(String cmd) throws Exception {
        String[] chaves = {"pesquisar", "pesquisa", "procurar", "procura"};
        int inicio = -1;
        for (String chave : chaves) {
            int i = cmd.indexOf(chave);
            if (i >= 0) { inicio = i + chave.length(); break; }
        }

        String consulta = cmd.substring(inicio).trim();
        if (consulta.startsWith("por ")) consulta = consulta.substring(4).trim();
        if (consulta.startsWith("sobre ")) consulta = consulta.substring(6).trim();

        if (consulta.isEmpty()) {
            falar("O que devo pesquisar, senhor?");
            return;
        }

        falar("Pesquisando, senhor.");
        Desktop.getDesktop().browse(new URI("https://www.google.com/search?q=" + URLEncoder.encode(consulta, "UTF-8")));
    }

    static long extrairSegundos(String cmd) {
        long total = 0;
        int acumulado = 0;
        boolean achou = false;

        for (String palavra : cmd.trim().split("\\s+")) {
            Integer n = NUMEROS.get(palavra);
            if (n == null && palavra.matches("\\d+")) n = Integer.parseInt(palavra);

            if (n != null) {
                acumulado += n;
                achou = true;
                continue;
            }

            if (palavra.equals("e") && achou) continue;

            if (achou) {
                if (palavra.startsWith("segundo")) total += acumulado;
                else if (palavra.startsWith("minuto")) total += acumulado * 60L;
                else if (palavra.startsWith("hora")) total += acumulado * 3600L;
            }

            acumulado = 0;
            achou = false;
        }

        return total;
    }

    static String descreverDuracao(long total) {
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        List<String> partes = new ArrayList<String>();
        if (h > 0) partes.add(h == 1 ? "uma hora" : h + " horas");
        if (m > 0) partes.add(m == 1 ? "um minuto" : m + " minutos");
        if (s > 0) partes.add(s == 1 ? "um segundo" : s + " segundos");
        return String.join(" e ", partes);
    }

    static final List<Comando> APPS    = new ArrayList<Comando>();
    static final List<Comando> SISTEMA = new ArrayList<Comando>();

    static {
        APPS.add(new Comando(c -> falarLista(
            "No modo I A, eu converso e respondo perguntas, senhor. Para ativar, diga modo I A.",
            "Escrevo rascunhos de email no Gmail e preparo mensagens de WhatsApp para os seus contatos.",
            "Pesquiso na Wikipédia, toco músicas no YouTube ou no Spotify, controlo a mídia e o volume, e abro programas da minha lista.",
            "Resumo, traduzo, corrijo, explico ou leio o texto ou o link que você copiou. Se o Gmail estiver configurado, resumo seus últimos emails e aviso quando chega um novo. Também resumo arquivos de texto, PDF ou Word pela interface."),
            "ia pode fazer", "i a pode fazer", "e a pode fazer", "e ar pode fazer", "de a pode fazer",
            "inteligência artificial pode fazer", "inteligencia artificial pode fazer",
            "funções da ia", "funcoes da ia", "funcoes do nexus", "o que aí", "o que e ar", "o que a e a faz",
            "o que o modo ia faz", "modo ia pode"));

        APPS.add(new Comando(c -> falarLista(
            "Sem o modo I A, eu faço o seguinte, senhor. Abro o YouTube, o Google, o Claude, a calculadora, o Spotify, o Discord, o Valorant, o Visual Studio Code, o Eclipse e os programas da minha lista.",
            "Digo as horas, a data de hoje, o clima, a temperatura da CPU e da GPU e o desempenho do computador. Pesquiso no Google e crio timers.",
            "Controlo o volume e a música: pausar, continuar, próxima e anterior. Também bloqueio o computador.",
            "Desligo ou reinicio o computador, com confirmação, e posso cancelar o desligamento. Digo hora de jogar para abrir a Steam e o Spotify, ou fechar tudo para encerrar vários programas."),
            "o que você pode fazer", "o que voce pode fazer", "o que você faz", "o que voce faz",
            "o que pode fazer", "o que você sabe fazer", "o que voce sabe fazer",
            "suas funções", "suas funcoes", "quais comandos", "quais os comandos",
            "quais são os comandos", "lista de comandos", "ajuda"));

        APPS.add(new Comando(c -> pesquisar(c),
            "pesquisa", "pesquisar", "procura", "procurar"));

        APPS.add(new Comando(c -> {
            long segundos = extrairSegundos(c);
            if (segundos <= 0) {
                falar("Por quanto tempo, senhor? Diga, por exemplo, timer de dez minutos.");
                return;
            }
            final String duracao = descreverDuracao(segundos);
            final long espera = segundos;
            falar("Timer de " + duracao + " iniciado.");
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(espera * 1000);
                    falar("Senhor, o timer de " + duracao + " terminou.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            t.setDaemon(true);
            t.start();
        }, "timer", "temporizador", "cronômetro", "cronometro", "alarme"));

        APPS.add(new Comando(c -> {
            if (c.contains("mudo")) {
                teclaMidia(173, 1);
                falar("Alternando o mudo, senhor.");
            } else if (c.contains("aumenta") || c.contains("sobe") || c.contains("subir") || c.contains("mais") || c.contains("alto")) {
                teclaMidia(175, 5);
                falar("Volume aumentado.");
            } else if (c.contains("diminui") || c.contains("baixa") || c.contains("baixar") || c.contains("menos") || c.contains("baixo")) {
                teclaMidia(174, 5);
                falar("Volume diminuído.");
            } else {
                falar("Diga aumentar ou diminuir o volume, senhor.");
            }
        }, "volume", "mudo"));

        APPS.add(new Comando(c -> {
            if (c.contains("próxima") || c.contains("proxima") || c.contains("pula")) {
                teclaMidia(176, 1);
            } else if (c.contains("anterior") || c.contains("volta")) {
                teclaMidia(177, 1);
            } else {
                teclaMidia(179, 1);
            }
            falar("Feito, senhor.");
        }, "pausa", "continua", "próxima", "proxima", "pula", "anterior", "volta",
           "toca", "play", "começa", "comeca"));

        APPS.add(new Comando(c -> {
            falar("Bloqueando o computador, senhor.");
            new ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start();
        }, "bloquear", "bloqueia", "bloqueio"));

        APPS.add(new Comando(c -> falarDesempenho(),
            "desempenho", "consumo", "uso de cpu", "uso da cpu", "uso do processador",
            "memória", "memoria", "processador", "como está o pc", "como esta o pc"));

        APPS.add(new Comando(c -> falarClima(),
            "clima", "chuva", "chover", "previsão", "previsao"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o Youtube");
            abrirUrl("https://youtube.com");
        }, "youtube", "outubro"));

        APPS.add(new Comando(c -> {
            falar("Isso de novo.");
            abrirUrl("https://spinthewheel.io/pt");
        }, "roleta"));

        APPS.add(new Comando(c -> falarTemperaturas(),
            "temperatura"));

        APPS.add(new Comando(c -> {
            boolean pedeHora = c.contains("horas");
            boolean pedeData = c.contains("que dia") || c.contains("data de hoje")
                || c.contains("qual a data") || c.contains("que de hoje");
            falarDataHora(pedeData, pedeHora);
        }, "horas", "que dia", "data de hoje", "qual a data", "que de hoje", "oração"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o Brave");
            abrirUrl("https://google.com");
        }, "google", "brave"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o Claude");
            abrirUrl("https://claude.ai/");
        }, "claude", "claudio"));

        APPS.add(new Comando(c -> {
            falar("Abrindo a Calculadora");
            new ProcessBuilder("cmd", "/c", "start", "calc").start();
        }, "calculadora"));
        
        APPS.add(new Comando(c -> {
            falar("Abrindo a Steam");
            new ProcessBuilder("cmd", "/c", "start", "calc").start();
        }, "steam", "jogo"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o visual estudios code");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:\\Users\\gh880\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe'").start();
        }, "programar", "programa", "veste code"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o eclipse");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:\\Users\\gh880\\eclipse\\java-2025-09\\eclipse\\eclipse.exe'").start();
        }, "java", "eclipse"));

        APPS.add(new Comando(c -> {
            falar("Vai jogar o que senhor?");
            try {
                new ProcessBuilder(
                    "C:\\Program Files (x86)\\Steam\\steam.exe",
                    "-no-browser",
                    "-nofriendsui").start();
                Thread.sleep(10000);
                new ProcessBuilder("cmd", "/c", "start", "", "steam://open/bigpicture").start();
            } catch (Exception e) {
                e.printStackTrace();
                falar("Erro ao abrir a Steam senhor.");
            }
            falar("Também vou abrir o spotify que eu sei que você gosta");
            new ProcessBuilder("cmd", "/c", "start", "spotify").start();
        }, "hora de jogar"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o Spotify");
            new ProcessBuilder("cmd", "/c", "start", "spotify").start();
        }, "esportes", "esporte", "pai", "se pode"));

        APPS.add(new Comando(c -> {
            falar("Abrindo o Valorant");
            new ProcessBuilder("powershell", "-Command",
                "Start-Process 'C:/Riot Games/Riot Client/RiotClientServices.exe'").start();
            falar("Também vou abrir o spotify que eu sei que você gosta");
            new ProcessBuilder("cmd", "/c", "start", "spotify").start();
        }, "valor", "valores", "valorante"));

        APPS.add(new Comando(c -> {
            falar("Vai falar com os manos!!");
            new ProcessBuilder("C:/Users/gh880/AppData/Local/Discord/Update.exe",
                "--processStart", "Discord.exe").start();
        }, "discord", "discorde", "escolha", "discã³rdia"));

        SISTEMA.add(new Comando(c -> pedirConfirmacao(
            "Tem certeza que quer desligar o computador, senhor? Diga sim ou não.",
            x -> {
                falar("Até a proxima, desligando em 30 segundos.");
                new ProcessBuilder("cmd", "/c", "shutdown /s /t 30").start();
            }), "desligar computador", "ligar computador", "desligar o computador"));

        SISTEMA.add(new Comando(c -> pedirConfirmacao(
            "Tem certeza que quer reiniciar o computador, senhor? Diga sim ou não.",
            x -> {
                falar("Reiniciando computador em 30 segundos.");
                new ProcessBuilder("cmd", "/c", "shutdown /r /t 30").start();
            }), "reinicia computador", "inicia computador", "reinicia o computador"));

        SISTEMA.add(new Comando(c -> {
            falar("Sim senhor, desligamento cancelado.");
            new ProcessBuilder("cmd", "/c", "shutdown /a").start();
        }, "cancelar", "cancela"));

        SISTEMA.add(new Comando(c -> pedirConfirmacao(
            "Vou fechar Steam, EA, Brave e outros programas, senhor. Confirma? Diga sim ou não.",
            x -> {
                falar("Caramba faço tudo nessa casa.");
                String[] appsParaFechar = {
                    "EALauncher.exe", "EALauncherHelper.exe", "upc.exe",
                    "UplayWebCore.exe", "EACefSubProcess.exe", "EABackgroundService.exe",
                    "EADesktop.exe", "steam.exe", "steamwebhelper.exe",
                    "TranslucentTB.exe", "brave.exe", "wallpaper64.exe",
                    "sqlceip.exe", "sqlservr.exe", "RvRvpnGui.exe"
                };
                for (String app : appsParaFechar) {
                    try { new ProcessBuilder("taskkill", "/F", "/IM", app, "/T").start(); }
                    catch (Exception ignored) {}
                }
            }), "fechar tudo", "deixar tudo", "bagunã§a"));
        
        
    }

    static void executarComando(String texto) throws Exception {
        if (JarvisGUI.interceptar(texto)) return;

        if (JarvisIA.ativo) {
            JarvisIA.tratar(texto);
            return;
        }

        if (JarvisIA.pedidoDeAtivar(texto)) {
            JarvisIA.ativar();
            return;
        }

        if (acaoPendente != null) {
            Acao pendente = acaoPendente;
            acaoPendente = null;
            if (System.currentTimeMillis() <= pendenteAte) {
                if (temPalavra(texto, "sim", "confirmo", "confirma")) {
                    pendente.executar(texto);
                    return;
                }
                if (temPalavra(texto, "não", "nao", "cancela", "cancelar")) {
                    falar("Ok, senhor, cancelado.");
                    return;
                }
            }
        }

        if (!temPalavra(texto, PALAVRAS_COMANDO)) return;

        String cmd = semPalavrasComando(texto);

        if (temPalavra(cmd, "encerrar", "tchau")) {
            falar("Até mais, senhor.");
            System.exit(0);
        }

        for (Comando comando : APPS) {
            if (comando.combina(cmd)) {
                comando.acao.executar(cmd);
                return;
            }
        }

        for (Comando comando : SISTEMA) {
            if (comando.combina(cmd)) {
                comando.acao.executar(cmd);
                return;
            }
        }
        JarvisIA.abrirPorComando(cmd);
    }

    static boolean pedidoParaInterromper(String texto) {
        String t = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(new Locale("pt", "BR")).trim();
        String[] frases = {
            "para", "pare", "parar", "para de falar", "pare de falar", "cala", "cala a boca",
            "silencio", "silêncio", "fica quieto", "chega", "interrompe", "interromper",
            "cancela a resposta", "cancele", "espera", "um momento"
        };
        for (String frase : frases) {
            if (t.equals(frase) || t.contains(frase)) return true;
        }
        return false;
    }

    public static void processar(final String texto) {
        processar(texto, null);
    }

    public static void processar(final String texto, final byte[] audio) {
        if (texto == null || texto.trim().isEmpty()) return;

        System.out.println("Reconhecido: " + texto);
        JarvisGUI.registrarUsuario(texto);

        if (JarvisIA.ativo) {
            JarvisIA.cancelarRespostaAnterior();
        } else if (falando) {
            pararFala();
        }

        EXECUTOR.execute(() -> {
            try {
                String entendido = texto;
                if (JarvisIA.ativo && audio != null && !JarvisIA.pedidoDeParar(texto)) {
                    String melhor = JarvisIA.transcrever(audio);
                    if (melhor != null) {
                        System.out.println("Whisper: " + melhor);
                        entendido = melhor;
                    }
                }
                executarComando(entendido);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        if (!JarvisGUI.instanciaUnica()) return;
        if (!SystemTray.isSupported()) {
            System.out.println("SystemTray não suportado.");
            return;
        }

        File pastaModelo = new File("model/vosk-model-pt-fb-v0.1.1-20220516_2113");
        if (!pastaModelo.isDirectory()) {
            System.out.println("Modelo Vosk não encontrado: " + pastaModelo.getAbsolutePath());
            System.out.println("Extraia o modelo completo para a pasta model/vosk.");
            return;
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.CYAN);
        g.fillOval(0, 0, 16, 16);
        g.dispose();

        PopupMenu menu = new PopupMenu();
        MenuItem abrirGui = new MenuItem("Abrir interface");
        abrirGui.addActionListener(e -> JarvisGUI.mostrar());
        menu.add(abrirGui);
        MenuItem sair = new MenuItem("Sair");
        sair.addActionListener(e -> System.exit(0));
        menu.add(sair);

        final TrayIcon trayIcon = new TrayIcon(image, "Jarvis", menu);
        trayIcon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(trayIcon);

        trayIcon.addActionListener(e -> JarvisGUI.mostrar());

        falar("Jarvis iniciado.");
        trayIcon.displayMessage("Jarvis",
            "Diga 'Jarvis abre X' para abrir apps | 'modo IA' ou duplo clique no ícone para conversar com a IA",
            TrayIcon.MessageType.INFO);

        AudioFormat formato = new AudioFormat(16000, 16, 1, true, false);
        TargetDataLine mic = AudioSystem.getTargetDataLine(formato);

        final String gramaticaInterrupcao = "[\"para\", \"pare\", \"parar\", \"para de falar\", \"cala\", \"cala a boca\", \"silencio\", \"fica quieto\", \"chega\", \"interrompe\", \"interromper\", \"cancela a resposta\", \"cancele\", \"espera\", \"um momento\"]";
		try (Model modelo = new Model(pastaModelo.getAbsolutePath());
		     Recognizer reconhecedor = new Recognizer(modelo, 16000)) {
		
		    mic.open(formato);
		    mic.start();
		
		    byte[] buffer = new byte[3200];
		
		    System.out.println("Vosk iniciado. Pode falar.");
		
		    final java.util.LinkedList<byte[]> preRoll = new java.util.LinkedList<byte[]>();
		    ByteArrayOutputStream captura = null;

		    while (true) {
		        int bytesLidos = mic.read(buffer, 0, buffer.length);
		        if (bytesLidos <= 0) continue;
		        byte[] pedaco = Arrays.copyOf(buffer, bytesLidos);

		        if (reconhecedor.acceptWaveForm(buffer, bytesLidos)) {
		            JSONObject json = new JSONObject(reconhecedor.getResult());

		            String texto = json.optString("text", "")
		                .trim()
		                .toLowerCase(new Locale("pt", "BR"));

		            byte[] audio = null;
		            if (captura != null) {
		                captura.write(pedaco, 0, pedaco.length);
		                audio = captura.toByteArray();
		                captura = null;
		            }
		            preRoll.clear();

		            if (!texto.isEmpty()) {
		                processar(texto, audio);
		            }
		        } else if (JarvisIA.ativo) {
		            String parcial = new JSONObject(reconhecedor.getPartialResult())
		                .optString("partial", "")
		                .trim()
		                .toLowerCase(new Locale("pt", "BR"));

		            if (falando && !parcial.isEmpty() && JarvisIA.pedidoDeParar(parcial)) {
		                JarvisIA.cancelarRespostaAnterior();
		                reconhecedor.reset();
		                captura = null;
		                preRoll.clear();
		                continue;
		            }

		            if (captura != null) {
		                captura.write(pedaco, 0, pedaco.length);
		                if (captura.size() > 960000) captura = null;
		            } else if (!parcial.isEmpty()) {
		                captura = new ByteArrayOutputStream();
		                for (byte[] antigo : preRoll) captura.write(antigo, 0, antigo.length);
		                captura.write(pedaco, 0, pedaco.length);
		                preRoll.clear();
		            } else {
		                preRoll.addLast(pedaco);
		                if (preRoll.size() > 4) preRoll.removeFirst();
		            }
		        } else {
		            captura = null;
		            preRoll.clear();
		        }
		    }
		} finally {
		    mic.stop();
		    mic.close();
		}
    }
}