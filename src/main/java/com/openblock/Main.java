package com.openblock;

public class Main {
    public static void main(String[] args) {
        // Force AWT headless so BufferedImage/Graphics2D works without a native display
        System.setProperty("java.awt.headless", "true");
        new Game().run();
    }
}
