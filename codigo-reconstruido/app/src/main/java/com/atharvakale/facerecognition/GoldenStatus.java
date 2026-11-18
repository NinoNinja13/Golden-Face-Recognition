package com.atharvakale.facerecognition;

/** Translate only known status strings/prefixes, never arbitrary face names or payloads. */
final class GoldenStatus {
    private static final String[][] PAIRS = {
        {"Serviço em segundo plano: parado", "Background service: stopped"},
        {"Serviço em segundo plano: iniciando câmera", "Background service: starting camera"},
        {"Reconhecimento pausado: tela apagada (câmera liberando)", "Recognition paused: screen off (releasing camera)"},
        {"Câmera em segundo plano: aberta", "Background camera: open"},
        {"Câmera em segundo plano: abrindo", "Background camera: opening"},
        {"Câmera em segundo plano: aguardando liberação", "Background camera: waiting for release"},
        {"Câmera em segundo plano: fechando", "Background camera: closing"},
        {"Câmera em segundo plano: fechada", "Background camera: closed"}
    };
    private static final String[][] PREFIXES = {
        {"Câmera em segundo plano: erro ", "Background camera: error "},
        {"Falha ao iniciar serviço: ", "Failed to start service: "},
        {"Falha no serviço: ", "Service failure: "}
    };
    static String localize(String s, boolean english) {
        if (s == null) return null;
        for (String[] p : PAIRS) if (s.equals(p[0]) || s.equals(p[1])) return p[english ? 1 : 0];
        for (String[] p : PREFIXES) for (int i = 0; i < 2; i++)
            if (s.startsWith(p[i])) return p[english ? 1 : 0] + s.substring(p[i].length());
        return s;
    }
}
