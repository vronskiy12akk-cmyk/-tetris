#!/usr/bin/env node
// tetris.js
const readline = require('readline');
const fs = require('fs');
const chalk = require('chalk');

const WIDTH = 10;
const HEIGHT = 20;

const SHAPES = {
    I: [[1,1,1,1]],
    O: [[1,1],[1,1]],
    T: [[0,1,0],[1,1,1]],
    S: [[0,1,1],[1,1,0]],
    Z: [[1,1,0],[0,1,1]],
    J: [[1,0,0],[1,1,1]],
    L: [[0,0,1],[1,1,1]]
};
const COLORS = {
    I: 'cyan', O: 'yellow', T: 'magenta', S: 'green', Z: 'red', J: 'blue', L: 'white'
};

class Tetris {
    constructor() {
        this.width = WIDTH;
        this.height = HEIGHT;
        this.board = Array.from({ length: HEIGHT }, () => Array(WIDTH).fill(0));
        this.score = 0;
        this.level = 1;
        this.linesCleared = 0;
        this.gameOver = false;
        this.paused = false;
        this.currentPiece = null;
        this.nextPiece = null;
        this.fallInterval = 1.0;
        this.fallTime = 0;
        this.highScore = this.loadHighScore();
        this.running = true;
        this.rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout
        });
        readline.emitKeypressEvents(process.stdin);
        process.stdin.setRawMode(true);
        this.nextPiece = this.newPiece();
        this.spawnPiece();
        this.setupInput();
    }

    loadHighScore() {
        try {
            const data = fs.readFileSync('tetris_score.json');
            return JSON.parse(data).high_score || 0;
        } catch { return 0; }
    }

    saveHighScore() {
        fs.writeFileSync('tetris_score.json', JSON.stringify({ high_score: this.highScore }));
    }

    newPiece() {
        const names = ['I','O','T','S','Z','J','L'];
        const name = names[Math.floor(Math.random() * names.length)];
        const shape = SHAPES[name].map(row => [...row]);
        const x = Math.floor((WIDTH - shape[0].length) / 2);
        return { shape, x, y: 0, name, color: COLORS[name] };
    }

    spawnPiece() {
        if (this.nextPiece) {
            this.currentPiece = this.nextPiece;
        } else {
            this.currentPiece = this.newPiece();
        }
        this.nextPiece = this.newPiece();
        if (!this.validPosition(this.currentPiece.shape, this.currentPiece.x, this.currentPiece.y)) {
            this.gameOver = true;
        }
    }

    validPosition(shape, offX, offY) {
        for (let y = 0; y < shape.length; y++) {
            for (let x = 0; x < shape[y].length; x++) {
                if (shape[y][x]) {
                    const newX = offX + x;
                    const newY = offY + y;
                    if (newX < 0 || newX >= WIDTH || newY >= HEIGHT) return false;
                    if (newY >= 0 && this.board[newY][newX] !== 0) return false;
                }
            }
        }
        return true;
    }

    lockPiece() {
        if (!this.currentPiece) return;
        const { shape, x, y } = this.currentPiece;
        for (let row = 0; row < shape.length; row++) {
            for (let col = 0; col < shape[row].length; col++) {
                if (shape[row][col]) {
                    const boardY = y + row;
                    const boardX = x + col;
                    if (boardY < 0) { this.gameOver = true; return; }
                    this.board[boardY][boardX] = 1; // цвет будет отображаться через цвет фигуры
                }
            }
        }
        this.clearLines();
        this.spawnPiece();
    }

    clearLines() {
        let cleared = 0;
        const newBoard = [];
        for (const row of this.board) {
            if (row.some(cell => cell === 0)) {
                newBoard.push(row);
            } else {
                cleared++;
            }
        }
        for (let i = 0; i < cleared; i++) {
            newBoard.unshift(Array(WIDTH).fill(0));
        }
        this.board = newBoard;
        if (cleared > 0) {
            this.linesCleared += cleared;
            const scores = [0, 100, 300, 500, 800];
            this.score += scores[Math.min(cleared, 4)] * this.level;
            this.level = Math.floor(this.linesCleared / 10) + 1;
            this.fallInterval = Math.max(0.1, 1.0 - (this.level - 1) * 0.07);
            if (this.score > this.highScore) {
                this.highScore = this.score;
                this.saveHighScore();
            }
        }
    }

    movePiece(dx, dy) {
        if (this.gameOver || this.paused || !this.currentPiece) return false;
        const newX = this.currentPiece.x + dx;
        const newY = this.currentPiece.y + dy;
        if (this.validPosition(this.currentPiece.shape, newX, newY)) {
            this.currentPiece.x = newX;
            this.currentPiece.y = newY;
            return true;
        }
        return false;
    }

    rotatePiece() {
        if (this.gameOver || this.paused || !this.currentPiece) return;
        const shape = this.currentPiece.shape;
        const rotated = shape[0].map((val, index) => shape.map(row => row[index]).reverse());
        if (this.validPosition(rotated, this.currentPiece.x, this.currentPiece.y)) {
            this.currentPiece.shape = rotated;
        }
    }

    hardDrop() {
        if (this.gameOver || this.paused || !this.currentPiece) return;
        while (this.movePiece(0, 1)) {}
        this.lockPiece();
    }

    update(dt) {
        if (this.gameOver || this.paused) return;
        this.fallTime += dt;
        if (this.fallTime >= this.fallInterval) {
            this.fallTime = 0;
            if (!this.movePiece(0, 1)) {
                this.lockPiece();
            }
        }
    }

    draw() {
        console.clear();
        console.log(chalk.cyan(`Тетрис (с физикой)   Счёт: ${this.score}   Уровень: ${this.level}   Рекорд: ${this.highScore}`));
        console.log('┌' + '─'.repeat(this.width * 2 + 1) + '┐');
        for (let y = 0; y < this.height; y++) {
            let line = '│';
            for (let x = 0; x < this.width; x++) {
                let cell = this.board[y][x];
                let drawn = false;
                if (this.currentPiece && !this.gameOver) {
                    const { shape, x: px, y: py, color } = this.currentPiece;
                    for (let row = 0; row < shape.length; row++) {
                        for (let col = 0; col < shape[row].length; col++) {
                            if (shape[row][col] && py + row === y && px + col === x) {
                                line += this.colorize('██', color);
                                drawn = true;
                                break;
                            }
                        }
                        if (drawn) break;
                    }
                }
                if (!drawn) {
                    if (cell !== 0) {
                        line += chalk.white('██');
                    } else {
                        line += '  ';
                    }
                }
            }
            line += '│';
            console.log(line);
        }
        console.log('└' + '─'.repeat(this.width * 2 + 1) + '┘');
        if (this.nextPiece) {
            console.log('Следующая:');
            for (const row of this.nextPiece.shape) {
                let line = '  ';
                for (const cell of row) {
                    if (cell) {
                        line += this.colorize('██', this.nextPiece.color);
                    } else {
                        line += '  ';
                    }
                }
                console.log(line);
            }
        }
        if (this.gameOver) console.log(chalk.red('ИГРА ОКОНЧЕНА! Нажмите R для рестарта'));
        else if (this.paused) console.log(chalk.yellow('ПАУЗА'));
    }

    colorize(str, colorName) {
        const colors = {
            cyan: chalk.cyan, yellow: chalk.yellow, magenta: chalk.magenta,
            green: chalk.green, red: chalk.red, blue: chalk.blue, white: chalk.white
        };
        return (colors[colorName] || chalk.white)(str);
    }

    setupInput() {
        process.stdin.on('keypress', (str, key) => {
            if (!key) return;
            const name = key.name;
            if (name === 'q') this.running = false;
            else if (name === 'p') this.paused = !this.paused;
            else if (name === 'r' && this.gameOver) {
                Object.assign(this, new Tetris());
                this.running = true;
            } else if (!this.paused && !this.gameOver) {
                if (name === 'left' || name === 'a') this.movePiece(-1, 0);
                else if (name === 'right' || name === 'd') this.movePiece(1, 0);
                else if (name === 'up' || name === 'w') this.rotatePiece();
                else if (name === 'down' || name === 's') this.movePiece(0, 1);
                else if (name === 'space') this.hardDrop();
            }
        });
    }

    run() {
        console.log(chalk.cyan('Тетрис (с физикой)'));
        console.log('Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход');
        console.log('Нажмите любую клавишу для начала...');
        process.stdin.once('keypress', () => {
            let lastTime = Date.now();
            const loop = () => {
                if (!this.running) {
                    process.exit(0);
                }
                const now = Date.now();
                const dt = (now - lastTime) / 1000;
                lastTime = now;
                this.update(dt);
                this.draw();
                setTimeout(loop, 30);
            };
            loop();
        });
    }
}

const game = new Tetris();
game.run();
