// tetris.go
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"math/rand"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	WIDTH  = 10
	HEIGHT = 20
)

type Piece struct {
	Shape [][]int
	X     int
	Y     int
	Name  string
	Color string
}

type Tetris struct {
	Board          [][]int
	Score          int
	Level          int
	LinesCleared   int
	GameOver       bool
	Paused         bool
	CurrentPiece   *Piece
	NextPiece      *Piece
	FallInterval   float64
	FallTime       float64
	HighScore      int
	Running        bool
	InputChan      chan string
}

func NewTetris() *Tetris {
	t := &Tetris{
		Board:        make([][]int, HEIGHT),
		Score:        0,
		Level:        1,
		LinesCleared: 0,
		GameOver:     false,
		Paused:       false,
		FallInterval: 1.0,
		FallTime:     0.0,
		HighScore:    0,
		Running:      true,
		InputChan:    make(chan string, 10),
	}
	for i := range t.Board {
		t.Board[i] = make([]int, WIDTH)
	}
	t.HighScore = t.loadHighScore()
	t.NextPiece = t.newPiece()
	t.spawnPiece()
	return t
}

func (t *Tetris) loadHighScore() int {
	data, err := os.ReadFile("tetris_score.json")
	if err != nil {
		return 0
	}
	var score map[string]int
	if err := json.Unmarshal(data, &score); err != nil {
		return 0
	}
	return score["high_score"]
}

func (t *Tetris) saveHighScore() {
	data, _ := json.Marshal(map[string]int{"high_score": t.HighScore})
	os.WriteFile("tetris_score.json", data, 0644)
}

func (t *Tetris) newPiece() *Piece {
	shapes := map[string][][]int{
		"I": {{1, 1, 1, 1}},
		"O": {{1, 1}, {1, 1}},
		"T": {{0, 1, 0}, {1, 1, 1}},
		"S": {{0, 1, 1}, {1, 1, 0}},
		"Z": {{1, 1, 0}, {0, 1, 1}},
		"J": {{1, 0, 0}, {1, 1, 1}},
		"L": {{0, 0, 1}, {1, 1, 1}},
	}
	names := []string{"I", "O", "T", "S", "Z", "J", "L"}
	name := names[rand.Intn(len(names))]
	shape := shapes[name]
	colors := map[string]string{
		"I": "cyan", "O": "yellow", "T": "magenta",
		"S": "green", "Z": "red", "J": "blue", "L": "white",
	}
	x := (WIDTH - len(shape[0])) / 2
	return &Piece{
		Shape: shape,
		X:     x,
		Y:     0,
		Name:  name,
		Color: colors[name],
	}
}

func (t *Tetris) spawnPiece() {
	if t.NextPiece != nil {
		t.CurrentPiece = t.NextPiece
	} else {
		t.CurrentPiece = t.newPiece()
	}
	t.NextPiece = t.newPiece()
	if !t.validPosition(t.CurrentPiece.Shape, t.CurrentPiece.X, t.CurrentPiece.Y) {
		t.GameOver = true
	}
}

func (t *Tetris) validPosition(shape [][]int, x, y int) bool {
	for rowIdx, row := range shape {
		for colIdx, cell := range row {
			if cell == 1 {
				newX := x + colIdx
				newY := y + rowIdx
				if newX < 0 || newX >= WIDTH || newY >= HEIGHT {
					return false
				}
				if newY >= 0 && t.Board[newY][newX] != 0 {
					return false
				}
			}
		}
	}
	return true
}

func (t *Tetris) lockPiece() {
	if t.CurrentPiece == nil {
		return
	}
	for rowIdx, row := range t.CurrentPiece.Shape {
		for colIdx, cell := range row {
			if cell == 1 {
				boardY := t.CurrentPiece.Y + rowIdx
				boardX := t.CurrentPiece.X + colIdx
				if boardY < 0 {
					t.GameOver = true
					return
				}
				t.Board[boardY][boardX] = 1 // цвет будет отображаться
			}
		}
	}
	t.clearLines()
	t.spawnPiece()
}

func (t *Tetris) clearLines() {
	linesCleared := 0
	newBoard := make([][]int, 0, HEIGHT)
	for _, row := range t.Board {
		hasZero := false
		for _, cell := range row {
			if cell == 0 {
				hasZero = true
				break
			}
		}
		if hasZero {
			newBoard = append(newBoard, row)
		} else {
			linesCleared++
		}
	}
	for i := 0; i < linesCleared; i++ {
		newBoard = append([][]int{make([]int, WIDTH)}, newBoard...)
	}
	t.Board = newBoard
	if linesCleared > 0 {
		t.LinesCleared += linesCleared
		scores := []int{0, 100, 300, 500, 800}
		addScore := scores[linesCleared]
		if linesCleared > 4 {
			addScore = 800
		}
		t.Score += addScore * t.Level
		t.Level = t.LinesCleared/10 + 1
		t.FallInterval = 1.0 - float64(t.Level-1)*0.07
		if t.FallInterval < 0.1 {
			t.FallInterval = 0.1
		}
		if t.Score > t.HighScore {
			t.HighScore = t.Score
			t.saveHighScore()
		}
	}
}

func (t *Tetris) rotateShape(shape [][]int) [][]int {
	rows := len(shape)
	cols := len(shape[0])
	rotated := make([][]int, cols)
	for i := range rotated {
		rotated[i] = make([]int, rows)
	}
	for i := 0; i < rows; i++ {
		for j := 0; j < cols; j++ {
			rotated[j][rows-1-i] = shape[i][j]
		}
	}
	return rotated
}

func (t *Tetris) movePiece(dx, dy int) bool {
	if t.GameOver || t.Paused || t.CurrentPiece == nil {
		return false
	}
	newX := t.CurrentPiece.X + dx
	newY := t.CurrentPiece.Y + dy
	if t.validPosition(t.CurrentPiece.Shape, newX, newY) {
		t.CurrentPiece.X = newX
		t.CurrentPiece.Y = newY
		return true
	}
	return false
}

func (t *Tetris) rotatePiece() {
	if t.GameOver || t.Paused || t.CurrentPiece == nil {
		return
	}
	rotated := t.rotateShape(t.CurrentPiece.Shape)
	if t.validPosition(rotated, t.CurrentPiece.X, t.CurrentPiece.Y) {
		t.CurrentPiece.Shape = rotated
	}
}

func (t *Tetris) hardDrop() {
	if t.GameOver || t.Paused || t.CurrentPiece == nil {
		return
	}
	for t.movePiece(0, 1) {
	}
	t.lockPiece()
}

func (t *Tetris) update(dt float64) {
	if t.GameOver || t.Paused {
		return
	}
	t.FallTime += dt
	if t.FallTime >= t.FallInterval {
		t.FallTime = 0
		if !t.movePiece(0, 1) {
			t.lockPiece()
		}
	}
}

func (t *Tetris) draw() {
	clearScreen()
	fmt.Printf("Тетрис (с физикой)   Счёт: %d   Уровень: %d   Рекорд: %d\n", t.Score, t.Level, t.HighScore)
	fmt.Println("┌" + strings.Repeat("─", WIDTH*2+1) + "┐")
	for y := 0; y < HEIGHT; y++ {
		fmt.Print("│")
		for x := 0; x < WIDTH; x++ {
			cell := t.Board[y][x]
			drawn := false
			if t.CurrentPiece != nil && !t.GameOver {
				for rowIdx, row := range t.CurrentPiece.Shape {
					for colIdx, val := range row {
						if val == 1 && t.CurrentPiece.Y+rowIdx == y && t.CurrentPiece.X+colIdx == x {
							color := colorCode(t.CurrentPiece.Color)
							fmt.Print(color + "██" + resetColor())
							drawn = true
							break
						}
					}
					if drawn {
						break
					}
				}
			}
			if !drawn {
				if cell != 0 {
					fmt.Print("\033[37m██\033[0m")
				} else {
					fmt.Print("  ")
				}
			}
		}
		fmt.Println("│")
	}
	fmt.Println("└" + strings.Repeat("─", WIDTH*2+1) + "┘")
	if t.NextPiece != nil {
		fmt.Println("Следующая:")
		for _, row := range t.NextPiece.Shape {
			fmt.Print("  ")
			for _, cell := range row {
				if cell == 1 {
					color := colorCode(t.NextPiece.Color)
					fmt.Print(color + "██" + resetColor())
				} else {
					fmt.Print("  ")
				}
			}
			fmt.Println()
		}
	}
	if t.GameOver {
		fmt.Println("\033[31mИГРА ОКОНЧЕНА! Нажмите R для рестарта\033[0m")
	} else if t.Paused {
		fmt.Println("\033[33mПАУЗА\033[0m")
	}
}

func colorCode(name string) string {
	switch name {
	case "cyan": return "\033[36m"
	case "yellow": return "\033[33m"
	case "magenta": return "\033[35m"
	case "green": return "\033[32m"
	case "red": return "\033[31m"
	case "blue": return "\033[34m"
	default: return "\033[37m"
	}
}

func resetColor() string { return "\033[0m" }

func clearScreen() {
	cmd := exec.Command("clear")
	if runtime.GOOS == "windows" {
		cmd = exec.Command("cmd", "/c", "cls")
	}
	cmd.Stdout = os.Stdout
	cmd.Run()
}

func (t *Tetris) inputLoop() {
	scanner := bufio.NewScanner(os.Stdin)
	for t.Running {
		if scanner.Scan() {
			line := scanner.Text()
			t.InputChan <- line
		}
	}
}

func (t *Tetris) run() {
	fmt.Println("Тетрис (с физикой)")
	fmt.Println("Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход")
	fmt.Println("Нажмите Enter для начала...")
	fmt.Scanln()

	go t.inputLoop()

	lastTime := time.Now()
	for t.Running {
		now := time.Now()
		dt := now.Sub(lastTime).Seconds()
		lastTime = now

		// Обработка ввода
		select {
		case input := <-t.InputChan:
			switch input {
			case "q", "Q":
				t.Running = false
			case "p", "P":
				t.Paused = !t.Paused
			case "r", "R":
				if t.GameOver {
					*t = *NewTetris()
					t.Running = true
				}
			default:
				if !t.Paused && !t.GameOver {
					switch input {
					case "left", "a":
						t.movePiece(-1, 0)
					case "right", "d":
						t.movePiece(1, 0)
					case "up", "w":
						t.rotatePiece()
					case "down", "s":
						t.movePiece(0, 1)
					case " ":
						t.hardDrop()
					}
				}
			}
		default:
		}

		t.update(dt)
		t.draw()
		time.Sleep(30 * time.Millisecond)
	}
}

func main() {
	rand.Seed(time.Now().UnixNano())
	game := NewTetris()
	game.run()
}
