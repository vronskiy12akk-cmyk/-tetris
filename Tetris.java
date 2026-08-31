// Tetris.java
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Tetris {
    private static final int WIDTH = 10;
    private static final int HEIGHT = 20;
    private static final Map<String, int[][]> SHAPES = new HashMap<>();
    private static final Map<String, String> COLORS = new HashMap<>();

    static {
        SHAPES.put("I", new int[][]{{1,1,1,1}});
        SHAPES.put("O", new int[][]{{1,1},{1,1}});
        SHAPES.put("T", new int[][]{{0,1,0},{1,1,1}});
        SHAPES.put("S", new int[][]{{0,1,1},{1,1,0}});
        SHAPES.put("Z", new int[][]{{1,1,0},{0,1,1}});
        SHAPES.put("J", new int[][]{{1,0,0},{1,1,1}});
        SHAPES.put("L", new int[][]{{0,0,1},{1,1,1}});
        COLORS.put("I", "cyan");
        COLORS.put("O", "yellow");
        COLORS.put("T", "magenta");
        COLORS.put("S", "green");
        COLORS.put("Z", "red");
        COLORS.put("J", "blue");
        COLORS.put("L", "white");
    }

    static class Piece {
        int[][] shape;
        int x, y;
        String name;
        String color;
    }

    private int[][] board;
    private int score, level, linesCleared, highScore;
    private boolean gameOver, paused, running;
    private Piece currentPiece, nextPiece;
    private double fallInterval, fallTime;
    private BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();
    private Scanner scanner = new Scanner(System.in);

    public Tetris() {
        board = new int[HEIGHT][WIDTH];
        score = 0; level = 1; linesCleared = 0;
        gameOver = false; paused = false; running = true;
        fallInterval = 1.0;
        highScore = loadHighScore();
        nextPiece = newPiece();
        spawnPiece();
        startInputThread();
    }

    private int loadHighScore() {
        try {
            String json = new String(Files.readAllBytes(Paths.get("tetris_score.json")));
            Gson gson = new Gson();
            Map<String, Object> map = gson.fromJson(json, Map.class);
            return ((Number) map.getOrDefault("high_score", 0)).intValue();
        } catch (Exception e) { return 0; }
    }

    private void saveHighScore() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("high_score", highScore);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(map);
            Files.write(Paths.get("tetris_score.json"), json.getBytes());
        } catch (Exception e) {}
    }

    private Piece newPiece() {
        String[] names = {"I","O","T","S","Z","J","L"};
        String name = names[new Random().nextInt(names.length)];
        int[][] shape = new int[SHAPES.get(name).length][];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = SHAPES.get(name)[i].clone();
        }
        int x = (WIDTH - shape[0].length) / 2;
        Piece p = new Piece();
        p.shape = shape; p.x = x; p.y = 0; p.name = name; p.color = COLORS.get(name);
        return p;
    }

    private void spawnPiece() {
        if (nextPiece != null) {
            currentPiece = nextPiece;
        } else {
            currentPiece = newPiece();
        }
        nextPiece = newPiece();
        if (!validPosition(currentPiece.shape, currentPiece.x, currentPiece.y)) {
            gameOver = true;
        }
    }

    private boolean validPosition(int[][] shape, int offX, int offY) {
        for (int r = 0; r < shape.length; r++) {
            for (int c = 0; c < shape[r].length; c++) {
                if (shape[r][c] == 1) {
                    int newX = offX + c;
                    int newY = offY + r;
                    if (newX < 0 || newX >= WIDTH || newY >= HEIGHT) return false;
                    if (newY >= 0 && board[newY][newX] != 0) return false;
                }
            }
        }
        return true;
    }

    private void lockPiece() {
        if (currentPiece == null) return;
        for (int r = 0; r < currentPiece.shape.length; r++) {
            for (int c = 0; c < currentPiece.shape[r].length; c++) {
                if (currentPiece.shape[r][c] == 1) {
                    int boardY = currentPiece.y + r;
                    int boardX = currentPiece.x + c;
                    if (boardY < 0) { gameOver = true; return; }
                    board[boardY][boardX] = 1;
                }
            }
        }
        clearLines();
        spawnPiece();
    }

    private void clearLines() {
        int cleared = 0;
        int[][] newBoard = new int[HEIGHT][WIDTH];
        int idx = HEIGHT - 1;
        for (int y = HEIGHT-1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < WIDTH; x++) {
                if (board[y][x] == 0) { full = false; break; }
            }
            if (full) {
                cleared++;
            } else {
                newBoard[idx--] = board[y];
            }
        }
        for (int i = idx; i >= 0; i--) {
            newBoard[i] = new int[WIDTH];
        }
        board = newBoard;
        if (cleared > 0) {
            linesCleared += cleared;
            int[] scores = {0,100,300,500,800};
            score += scores[Math.min(cleared,4)] * level;
            level = linesCleared / 10 + 1;
            fallInterval = Math.max(0.1, 1.0 - (level-1) * 0.07);
            if (score > highScore) { highScore = score; saveHighScore(); }
        }
    }

    private int[][] rotateShape(int[][] shape) {
        int rows = shape.length, cols = shape[0].length;
        int[][] rotated = new int[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                rotated[j][rows-1-i] = shape[i][j];
            }
        }
        return rotated;
    }

    private boolean movePiece(int dx, int dy) {
        if (gameOver || paused || currentPiece == null) return false;
        int newX = currentPiece.x + dx;
        int newY = currentPiece.y + dy;
        if (validPosition(currentPiece.shape, newX, newY)) {
            currentPiece.x = newX;
            currentPiece.y = newY;
            return true;
        }
        return false;
    }

    private void rotatePiece() {
        if (gameOver || paused || currentPiece == null) return;
        int[][] rotated = rotateShape(currentPiece.shape);
        if (validPosition(rotated, currentPiece.x, currentPiece.y)) {
            currentPiece.shape = rotated;
        }
    }

    private void hardDrop() {
        if (gameOver || paused || currentPiece == null) return;
        while (movePiece(0, 1)) {}
        lockPiece();
    }

    private void update(double dt) {
        if (gameOver || paused) return;
        fallTime += dt;
        if (fallTime >= fallInterval) {
            fallTime = 0;
            if (!movePiece(0, 1)) {
                lockPiece();
            }
        }
    }

    private void draw() {
        clearScreen();
        System.out.printf("\u001B[36mТетрис (с физикой)   Счёт: %d   Уровень: %d   Рекорд: %d\u001B[0m%n", score, level, highScore);
        System.out.println("┌" + "─".repeat(WIDTH*2+1) + "┐");
        for (int y = 0; y < HEIGHT; y++) {
            System.out.print("│");
            for (int x = 0; x < WIDTH; x++) {
                boolean drawn = false;
                if (currentPiece != null && !gameOver) {
                    for (int r = 0; r < currentPiece.shape.length && !drawn; r++) {
                        for (int c = 0; c < currentPiece.shape[r].length && !drawn; c++) {
                            if (currentPiece.shape[r][c] == 1 && currentPiece.y + r == y && currentPiece.x + c == x) {
                                System.out.print(colorize("██", currentPiece.color));
                                drawn = true;
                            }
                        }
                    }
                }
                if (!drawn) {
                    if (board[y][x] != 0) {
                        System.out.print("\u001B[37m██\u001B[0m");
                    } else {
                        System.out.print("  ");
                    }
                }
            }
            System.out.println("│");
        }
        System.out.println("└" + "─".repeat(WIDTH*2+1) + "┘");
        if (nextPiece != null) {
            System.out.println("Следующая:");
            for (int[] row : nextPiece.shape) {
                System.out.print("  ");
                for (int cell : row) {
                    if (cell == 1) {
                        System.out.print(colorize("██", nextPiece.color));
                    } else {
                        System.out.print("  ");
                    }
                }
                System.out.println();
            }
        }
        if (gameOver) System.out.println("\u001B[31mИГРА ОКОНЧЕНА! Нажмите R для рестарта\u001B[0m");
        else if (paused) System.out.println("\u001B[33mПАУЗА\u001B[0m");
    }

    private String colorize(String str, String color) {
        switch(color) {
            case "cyan": return "\u001B[36m" + str + "\u001B[0m";
            case "yellow": return "\u001B[33m" + str + "\u001B[0m";
            case "magenta": return "\u001B[35m" + str + "\u001B[0m";
            case "green": return "\u001B[32m" + str + "\u001B[0m";
            case "red": return "\u001B[31m" + str + "\u001B[0m";
            case "blue": return "\u001B[34m" + str + "\u001B[0m";
            default: return "\u001B[37m" + str + "\u001B[0m";
        }
    }

    private void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private void startInputThread() {
        new Thread(() -> {
            while (running) {
                try {
                    if (System.in.available() > 0) {
                        char ch = (char) System.in.read();
                        inputQueue.offer(String.valueOf(ch));
                    }
                    Thread.sleep(20);
                } catch (Exception e) {}
            }
        }).start();
    }

    public void run() {
        System.out.println("\u001B[36mТетрис (с физикой)\u001B[0m");
        System.out.println("Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход");
        System.out.println("Нажмите Enter для начала...");
        scanner.nextLine();

        long lastTime = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            double dt = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            // Обработка ввода
            String input = inputQueue.poll();
            if (input != null) {
                char key = input.charAt(0);
                if (key == 'q' || key == 'Q') { running = false; break; }
                if (key == 'p' || key == 'P') { paused = !paused; }
                if ((key == 'r' || key == 'R') && gameOver) {
                    Tetris newGame = new Tetris();
                    this.board = newGame.board;
                    this.score = newGame.score;
                    this.level = newGame.level;
                    this.linesCleared = newGame.linesCleared;
                    this.gameOver = newGame.gameOver;
                    this.paused = newGame.paused;
                    this.currentPiece = newGame.currentPiece;
                    this.nextPiece = newGame.nextPiece;
                    this.fallInterval = newGame.fallInterval;
                    this.fallTime = newGame.fallTime;
                    this.highScore = newGame.highScore;
                    this.running = true;
                }
                if (!paused && !gameOver) {
                    switch (key) {
                        case 'a': movePiece(-1, 0); break;
                        case 'd': movePiece(1, 0); break;
                        case 'w': rotatePiece(); break;
                        case 's': movePiece(0, 1); break;
                        case ' ': hardDrop(); break;
                    }
                }
            }

            update(dt);
            draw();
            try { Thread.sleep(30); } catch (InterruptedException e) {}
        }
        System.out.println("Выход из игры.");
    }

    public static void main(String[] args) {
        Tetris game = new Tetris();
        game.run();
    }
}
