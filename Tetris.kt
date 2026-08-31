// Tetris.kt
import com.google.gson.GsonBuilder
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

class Tetris {
    companion object {
        const val WIDTH = 10
        const val HEIGHT = 20
        val SHAPES = mapOf(
            "I" to arrayOf(intArrayOf(1,1,1,1)),
            "O" to arrayOf(intArrayOf(1,1), intArrayOf(1,1)),
            "T" to arrayOf(intArrayOf(0,1,0), intArrayOf(1,1,1)),
            "S" to arrayOf(intArrayOf(0,1,1), intArrayOf(1,1,0)),
            "Z" to arrayOf(intArrayOf(1,1,0), intArrayOf(0,1,1)),
            "J" to arrayOf(intArrayOf(1,0,0), intArrayOf(1,1,1)),
            "L" to arrayOf(intArrayOf(0,0,1), intArrayOf(1,1,1))
        )
        val COLORS = mapOf(
            "I" to "cyan", "O" to "yellow", "T" to "magenta",
            "S" to "green", "Z" to "red", "J" to "blue", "L" to "white"
        )
    }

    data class Piece(var shape: Array<IntArray>, var x: Int, var y: Int, val name: String, val color: String)

    private var board = Array(HEIGHT) { IntArray(WIDTH) }
    private var score = 0
    private var level = 1
    private var linesCleared = 0
    private var highScore = 0
    private var gameOver = false
    private var paused = false
    private var running = true
    private var currentPiece: Piece? = null
    private var nextPiece: Piece? = null
    private var fallInterval = 1.0
    private var fallTime = 0.0
    private val inputQueue = mutableListOf<String>()
    private val lock = Any()

    init {
        highScore = loadHighScore()
        nextPiece = newPiece()
        spawnPiece()
        startInputThread()
    }

    private fun loadHighScore(): Int {
        return try {
            val json = File("tetris_score.json").readText()
            val map = GsonBuilder().create().fromJson(json, Map::class.java)
            (map["high_score"] as? Number)?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun saveHighScore() {
        val json = GsonBuilder().setPrettyPrinting().create().toJson(mapOf("high_score" to highScore))
        File("tetris_score.json").writeText(json)
    }

    private fun newPiece(): Piece {
        val names = listOf("I","O","T","S","Z","J","L")
        val name = names[Random.nextInt(names.size)]
        val shape = SHAPES[name]!!.map { it.clone() }.toTypedArray()
        val x = (WIDTH - shape[0].size) / 2
        return Piece(shape, x, 0, name, COLORS[name]!!)
    }

    private fun spawnPiece() {
        currentPiece = nextPiece ?: newPiece()
        nextPiece = newPiece()
        if (!validPosition(currentPiece!!.shape, currentPiece!!.x, currentPiece!!.y)) {
            gameOver = true
        }
    }

    private fun validPosition(shape: Array<IntArray>, offX: Int, offY: Int): Boolean {
        for (r in shape.indices) {
            for (c in shape[r].indices) {
                if (shape[r][c] == 1) {
                    val newX = offX + c
                    val newY = offY + r
                    if (newX < 0 || newX >= WIDTH || newY >= HEIGHT) return false
                    if (newY >= 0 && board[newY][newX] != 0) return false
                }
            }
        }
        return true
    }

    private fun lockPiece() {
        val piece = currentPiece ?: return
        for (r in piece.shape.indices) {
            for (c in piece.shape[r].indices) {
                if (piece.shape[r][c] == 1) {
                    val boardY = piece.y + r
                    val boardX = piece.x + c
                    if (boardY < 0) { gameOver = true; return }
                    board[boardY][boardX] = 1
                }
            }
        }
        clearLines()
        spawnPiece()
    }

    private fun clearLines() {
        var cleared = 0
        val newBoard = mutableListOf<IntArray>()
        for (y in 0 until HEIGHT) {
            if (board[y].any { it == 0 }) {
                newBoard.add(board[y])
            } else {
                cleared++
            }
        }
        repeat(cleared) { newBoard.add(0, IntArray(WIDTH)) }
        board = newBoard.toTypedArray()
        if (cleared > 0) {
            linesCleared += cleared
            val scores = arrayOf(0, 100, 300, 500, 800)
            score += scores[Math.min(cleared, 4)] * level
            level = linesCleared / 10 + 1
            fallInterval = Math.max(0.1, 1.0 - (level - 1) * 0.07)
            if (score > highScore) { highScore = score; saveHighScore() }
        }
    }

    private fun rotateShape(shape: Array<IntArray>): Array<IntArray> {
        val rows = shape.size
        val cols = shape[0].size
        val rotated = Array(cols) { IntArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                rotated[j][rows - 1 - i] = shape[i][j]
            }
        }
        return rotated
    }

    private fun movePiece(dx: Int, dy: Int): Boolean {
        if (gameOver || paused || currentPiece == null) return false
        val newX = currentPiece!!.x + dx
        val newY = currentPiece!!.y + dy
        if (validPosition(currentPiece!!.shape, newX, newY)) {
            currentPiece!!.x = newX
            currentPiece!!.y = newY
            return true
        }
        return false
    }

    private fun rotatePiece() {
        if (gameOver || paused || currentPiece == null) return
        val rotated = rotateShape(currentPiece!!.shape)
        if (validPosition(rotated, currentPiece!!.x, currentPiece!!.y)) {
            currentPiece!!.shape = rotated
        }
    }

    private fun hardDrop() {
        if (gameOver || paused || currentPiece == null) return
        while (movePiece(0, 1)) {}
        lockPiece()
    }

    private fun update(dt: Double) {
        if (gameOver || paused) return
        fallTime += dt
        if (fallTime >= fallInterval) {
            fallTime = 0.0
            if (!movePiece(0, 1)) {
                lockPiece()
            }
        }
    }

    private fun colorize(str: String, color: String): String {
        return when (color) {
            "cyan" -> "\u001B[36m$str\u001B[0m"
            "yellow" -> "\u001B[33m$str\u001B[0m"
            "magenta" -> "\u001B[35m$str\u001B[0m"
            "green" -> "\u001B[32m$str\u001B[0m"
            "red" -> "\u001B[31m$str\u001B[0m"
            "blue" -> "\u001B[34m$str\u001B[0m"
            else -> "\u001B[37m$str\u001B[0m"
        }
    }

    private fun draw() {
        print("\u001B[2J\u001B[1;1H")
        println("\u001B[36mТетрис (с физикой)   Счёт: $score   Уровень: $level   Рекорд: $highScore\u001B[0m")
        println("┌" + "─".repeat(WIDTH * 2 + 1) + "┐")
        for (y in 0 until HEIGHT) {
            print("│")
            for (x in 0 until WIDTH) {
                var drawn = false
                if (currentPiece != null && !gameOver) {
                    for (r in currentPiece!!.shape.indices) {
                        for (c in currentPiece!!.shape[r].indices) {
                            if (currentPiece!!.shape[r][c] == 1 && currentPiece!!.y + r == y && currentPiece!!.x + c == x) {
                                print(colorize("██", currentPiece!!.color))
                                drawn = true
                                break
                            }
                        }
                        if (drawn) break
                    }
                }
                if (!drawn) {
                    if (board[y][x] != 0) {
                        print("\u001B[37m██\u001B[0m")
                    } else {
                        print("  ")
                    }
                }
            }
            println("│")
        }
        println("└" + "─".repeat(WIDTH * 2 + 1) + "┘")
        if (nextPiece != null) {
            println("Следующая:")
            for (row in nextPiece!!.shape) {
                print("  ")
                for (cell in row) {
                    if (cell == 1) {
                        print(colorize("██", nextPiece!!.color))
                    } else {
                        print("  ")
                    }
                }
                println()
            }
        }
        if (gameOver) println("\u001B[31mИГРА ОКОНЧЕНА! Нажмите R для рестарта\u001B[0m")
        else if (paused) println("\u001B[33mПАУЗА\u001B[0m")
    }

    private fun startInputThread() {
        thread {
            while (running) {
                try {
                    if (System.`in`.available() > 0) {
                        val ch = System.`in`.read()
                        synchronized(lock) {
                            inputQueue.add(ch.toChar().toString())
                        }
                    }
                    Thread.sleep(20)
                } catch (_: Exception) {}
            }
        }
    }

    fun run() {
        println("\u001B[36mТетрис (с физикой)\u001B[0m")
        println("Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход")
        println("Нажмите Enter для начала...")
        readLine()

        var lastTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = (now - lastTime) / 1_000_000_000.0
            lastTime = now

            // Обработка ввода
            var input: String? = null
            synchronized(lock) {
                if (inputQueue.isNotEmpty()) {
                    input = inputQueue.removeAt(0)
                }
            }
            if (input != null) {
                when (input) {
                    "q", "Q" -> { running = false; break }
                    "p", "P" -> paused = !paused
                    "r", "R" -> if (gameOver) {
                        val newGame = Tetris()
                        this.board = newGame.board
                        this.score = newGame.score
                        this.level = newGame.level
                        this.linesCleared = newGame.linesCleared
                        this.gameOver = newGame.gameOver
                        this.paused = newGame.paused
                        this.currentPiece = newGame.currentPiece
                        this.nextPiece = newGame.nextPiece
                        this.fallInterval = newGame.fallInterval
                        this.fallTime = newGame.fallTime
                        this.highScore = newGame.highScore
                        this.running = true
                    }
                    else -> {
                        if (!paused && !gameOver) {
                            when (input) {
                                "a" -> movePiece(-1, 0)
                                "d" -> movePiece(1, 0)
                                "w" -> rotatePiece()
                                "s" -> movePiece(0, 1)
                                " " -> hardDrop()
                            }
                        }
                    }
                }
            }

            update(dt)
            draw()
            Thread.sleep(30)
        }
        println("Выход из игры.")
    }
}

fun main() {
    Tetris().run()
}
