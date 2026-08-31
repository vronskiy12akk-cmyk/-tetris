// Tetris.cs
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

class Tetris
{
    const int WIDTH = 10;
    const int HEIGHT = 20;

    static readonly Dictionary<string, int[][]> SHAPES = new()
    {
        ["I"] = new int[][] { new int[] {1,1,1,1} },
        ["O"] = new int[][] { new int[] {1,1}, new int[] {1,1} },
        ["T"] = new int[][] { new int[] {0,1,0}, new int[] {1,1,1} },
        ["S"] = new int[][] { new int[] {0,1,1}, new int[] {1,1,0} },
        ["Z"] = new int[][] { new int[] {1,1,0}, new int[] {0,1,1} },
        ["J"] = new int[][] { new int[] {1,0,0}, new int[] {1,1,1} },
        ["L"] = new int[][] { new int[] {0,0,1}, new int[] {1,1,1} }
    };
    static readonly Dictionary<string, string> COLORS = new()
    {
        ["I"] = "cyan", ["O"] = "yellow", ["T"] = "magenta",
        ["S"] = "green", ["Z"] = "red", ["J"] = "blue", ["L"] = "white"
    };

    class Piece
    {
        public int[][] Shape { get; set; }
        public int X { get; set; }
        public int Y { get; set; }
        public string Name { get; set; }
        public string Color { get; set; }
    }

    private int[][] board;
    private int score, level, linesCleared, highScore;
    private bool gameOver, paused, running;
    private Piece currentPiece, nextPiece;
    private double fallInterval, fallTime;
    private Random rand = new Random();
    private Queue<string> inputQueue = new Queue<string>();
    private object queueLock = new object();

    public Tetris()
    {
        board = new int[HEIGHT][];
        for (int i = 0; i < HEIGHT; i++) board[i] = new int[WIDTH];
        score = 0; level = 1; linesCleared = 0;
        gameOver = false; paused = false; running = true;
        fallInterval = 1.0;
        highScore = LoadHighScore();
        nextPiece = NewPiece();
        SpawnPiece();
        StartInputThread();
    }

    private int LoadHighScore()
    {
        try
        {
            string json = File.ReadAllText("tetris_score.json");
            var doc = JsonDocument.Parse(json);
            return doc.RootElement.GetProperty("high_score").GetInt32();
        }
        catch { return 0; }
    }

    private void SaveHighScore()
    {
        var json = JsonSerializer.Serialize(new { high_score = highScore });
        File.WriteAllText("tetris_score.json", json);
    }

    private Piece NewPiece()
    {
        string[] names = { "I", "O", "T", "S", "Z", "J", "L" };
        string name = names[rand.Next(names.Length)];
        var shape = SHAPES[name].Select(row => row.ToArray()).ToArray();
        int x = (WIDTH - shape[0].Length) / 2;
        return new Piece { Shape = shape, X = x, Y = 0, Name = name, Color = COLORS[name] };
    }

    private void SpawnPiece()
    {
        if (nextPiece != null)
            currentPiece = nextPiece;
        else
            currentPiece = NewPiece();
        nextPiece = NewPiece();
        if (!ValidPosition(currentPiece.Shape, currentPiece.X, currentPiece.Y))
            gameOver = true;
    }

    private bool ValidPosition(int[][] shape, int offX, int offY)
    {
        for (int r = 0; r < shape.Length; r++)
            for (int c = 0; c < shape[r].Length; c++)
                if (shape[r][c] == 1)
                {
                    int newX = offX + c;
                    int newY = offY + r;
                    if (newX < 0 || newX >= WIDTH || newY >= HEIGHT) return false;
                    if (newY >= 0 && board[newY][newX] != 0) return false;
                }
        return true;
    }

    private void LockPiece()
    {
        if (currentPiece == null) return;
        for (int r = 0; r < currentPiece.Shape.Length; r++)
            for (int c = 0; c < currentPiece.Shape[r].Length; c++)
                if (currentPiece.Shape[r][c] == 1)
                {
                    int boardY = currentPiece.Y + r;
                    int boardX = currentPiece.X + c;
                    if (boardY < 0) { gameOver = true; return; }
                    board[boardY][boardX] = 1;
                }
        ClearLines();
        SpawnPiece();
    }

    private void ClearLines()
    {
        int cleared = 0;
        var newBoard = new List<int[]>();
        for (int y = 0; y < HEIGHT; y++)
        {
            if (board[y].Any(cell => cell == 0))
                newBoard.Add(board[y]);
            else
                cleared++;
        }
        for (int i = 0; i < cleared; i++)
            newBoard.Insert(0, new int[WIDTH]);
        board = newBoard.ToArray();
        if (cleared > 0)
        {
            linesCleared += cleared;
            int[] scores = { 0, 100, 300, 500, 800 };
            score += scores[Math.Min(cleared, 4)] * level;
            level = linesCleared / 10 + 1;
            fallInterval = Math.Max(0.1, 1.0 - (level - 1) * 0.07);
            if (score > highScore) { highScore = score; SaveHighScore(); }
        }
    }

    private int[][] RotateShape(int[][] shape)
    {
        int rows = shape.Length, cols = shape[0].Length;
        var rotated = new int[cols][];
        for (int i = 0; i < cols; i++) rotated[i] = new int[rows];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                rotated[j][rows - 1 - i] = shape[i][j];
        return rotated;
    }

    private bool MovePiece(int dx, int dy)
    {
        if (gameOver || paused || currentPiece == null) return false;
        int newX = currentPiece.X + dx;
        int newY = currentPiece.Y + dy;
        if (ValidPosition(currentPiece.Shape, newX, newY))
        {
            currentPiece.X = newX;
            currentPiece.Y = newY;
            return true;
        }
        return false;
    }

    private void RotatePiece()
    {
        if (gameOver || paused || currentPiece == null) return;
        var rotated = RotateShape(currentPiece.Shape);
        if (ValidPosition(rotated, currentPiece.X, currentPiece.Y))
            currentPiece.Shape = rotated;
    }

    private void HardDrop()
    {
        if (gameOver || paused || currentPiece == null) return;
        while (MovePiece(0, 1)) { }
        LockPiece();
    }

    private void Update(double dt)
    {
        if (gameOver || paused) return;
        fallTime += dt;
        if (fallTime >= fallInterval)
        {
            fallTime = 0;
            if (!MovePiece(0, 1))
                LockPiece();
        }
    }

    private void Draw()
    {
        Console.Clear();
        Console.WriteLine($"\u001B[36mТетрис (с физикой)   Счёт: {score}   Уровень: {level}   Рекорд: {highScore}\u001B[0m");
        Console.WriteLine("┌" + new string('─', WIDTH * 2 + 1) + "┐");
        for (int y = 0; y < HEIGHT; y++)
        {
            Console.Write("│");
            for (int x = 0; x < WIDTH; x++)
            {
                bool drawn = false;
                if (currentPiece != null && !gameOver)
                {
                    for (int r = 0; r < currentPiece.Shape.Length && !drawn; r++)
                        for (int c = 0; c < currentPiece.Shape[r].Length && !drawn; c++)
                            if (currentPiece.Shape[r][c] == 1 && currentPiece.Y + r == y && currentPiece.X + c == x)
                            {
                                Console.Write(Colorize("██", currentPiece.Color));
                                drawn = true;
                            }
                }
                if (!drawn)
                {
                    if (board[y][x] != 0)
                        Console.Write("\u001B[37m██\u001B[0m");
                    else
                        Console.Write("  ");
                }
            }
            Console.WriteLine("│");
        }
        Console.WriteLine("└" + new string('─', WIDTH * 2 + 1) + "┘");
        if (nextPiece != null)
        {
            Console.WriteLine("Следующая:");
            foreach (var row in nextPiece.Shape)
            {
                Console.Write("  ");
                foreach (var cell in row)
                {
                    if (cell == 1)
                        Console.Write(Colorize("██", nextPiece.Color));
                    else
                        Console.Write("  ");
                }
                Console.WriteLine();
            }
        }
        if (gameOver) Console.WriteLine("\u001B[31mИГРА ОКОНЧЕНА! Нажмите R для рестарта\u001B[0m");
        else if (paused) Console.WriteLine("\u001B[33mПАУЗА\u001B[0m");
    }

    private string Colorize(string str, string color)
    {
        return color switch
        {
            "cyan" => "\u001B[36m" + str + "\u001B[0m",
            "yellow" => "\u001B[33m" + str + "\u001B[0m",
            "magenta" => "\u001B[35m" + str + "\u001B[0m",
            "green" => "\u001B[32m" + str + "\u001B[0m",
            "red" => "\u001B[31m" + str + "\u001B[0m",
            "blue" => "\u001B[34m" + str + "\u001B[0m",
            _ => "\u001B[37m" + str + "\u001B[0m"
        };
    }

    private void StartInputThread()
    {
        Task.Run(() =>
        {
            while (running)
            {
                if (Console.KeyAvailable)
                {
                    var key = Console.ReadKey(true).Key;
                    string input = key.ToString().ToLower();
                    if (key == ConsoleKey.Q) { running = false; break; }
                    if (key == ConsoleKey.P) { paused = !paused; }
                    if (key == ConsoleKey.R && gameOver)
                    {
                        var newGame = new Tetris();
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
                    if (!paused && !gameOver)
                    {
                        switch (key)
                        {
                            case ConsoleKey.LeftArrow: MovePiece(-1, 0); break;
                            case ConsoleKey.RightArrow: MovePiece(1, 0); break;
                            case ConsoleKey.UpArrow: RotatePiece(); break;
                            case ConsoleKey.DownArrow: MovePiece(0, 1); break;
                            case ConsoleKey.Spacebar: HardDrop(); break;
                        }
                    }
                }
                Thread.Sleep(20);
            }
        });
    }

    public void Run()
    {
        Console.WriteLine("\u001B[36mТетрис (с физикой)\u001B[0m");
        Console.WriteLine("Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход");
        Console.WriteLine("Нажмите Enter для начала...");
        Console.ReadLine();

        long lastTime = DateTime.Now.Ticks / TimeSpan.TicksPerMillisecond;
        while (running)
        {
            long now = DateTime.Now.Ticks / TimeSpan.TicksPerMillisecond;
            double dt = (now - lastTime) / 1000.0;
            lastTime = now;
            Update(dt);
            Draw();
            Thread.Sleep(30);
        }
    }

    public static void Main()
    {
        var game = new Tetris();
        game.Run();
    }
}
