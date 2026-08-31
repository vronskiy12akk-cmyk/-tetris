// tetris.rs
use std::collections::HashMap;
use std::io::{self, Write, Read};
use std::time::{Duration, Instant};
use rand::Rng;
use termion::raw::IntoRawMode;
use termion::input::TermRead;
use colored::*;

const WIDTH: usize = 10;
const HEIGHT: usize = 20;

type Shape = Vec<Vec<u8>>;

struct Piece {
    shape: Shape,
    x: usize,
    y: usize,
    name: String,
    color: String,
}

impl Piece {
    fn rotate(&self) -> Shape {
        let rows = self.shape.len();
        let cols = self.shape[0].len();
        let mut rotated = vec![vec![0; rows]; cols];
        for i in 0..rows {
            for j in 0..cols {
                rotated[j][rows - 1 - i] = self.shape[i][j];
            }
        }
        rotated
    }
}

struct Tetris {
    board: Vec<Vec<u8>>,
    score: u32,
    level: u32,
    lines_cleared: u32,
    game_over: bool,
    paused: bool,
    current_piece: Option<Piece>,
    next_piece: Option<Piece>,
    fall_interval: f64,
    fall_time: f64,
    high_score: u32,
    running: bool,
}

impl Tetris {
    fn new() -> Self {
        let mut tetris = Tetris {
            board: vec![vec![0; WIDTH]; HEIGHT],
            score: 0,
            level: 1,
            lines_cleared: 0,
            game_over: false,
            paused: false,
            current_piece: None,
            next_piece: None,
            fall_interval: 1.0,
            fall_time: 0.0,
            high_score: 0,
            running: true,
        };
        tetris.high_score = tetris.load_high_score();
        tetris.next_piece = Some(tetris.new_piece());
        tetris.spawn_piece();
        tetris
    }

    fn load_high_score(&self) -> u32 {
        if let Ok(data) = std::fs::read_to_string("tetris_score.json") {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&data) {
                if let Some(score) = json.get("high_score").and_then(|v| v.as_u64()) {
                    return score as u32;
                }
            }
        }
        0
    }

    fn save_high_score(&self) {
        let json = serde_json::json!({ "high_score": self.high_score });
        let _ = std::fs::write("tetris_score.json", serde_json::to_string_pretty(&json).unwrap());
    }

    fn new_piece(&self) -> Piece {
        let shapes: HashMap<&str, Shape> = [
            ("I", vec![vec![1,1,1,1]]),
            ("O", vec![vec![1,1], vec![1,1]]),
            ("T", vec![vec![0,1,0], vec![1,1,1]]),
            ("S", vec![vec![0,1,1], vec![1,1,0]]),
            ("Z", vec![vec![1,1,0], vec![0,1,1]]),
            ("J", vec![vec![1,0,0], vec![1,1,1]]),
            ("L", vec![vec![0,0,1], vec![1,1,1]]),
        ].iter().cloned().collect();
        let names = ["I","O","T","S","Z","J","L"];
        let name = names[rand::thread_rng().gen_range(0..names.len())];
        let shape = shapes[name].unwrap().clone();
        let colors = HashMap::from([
            ("I", "cyan"), ("O", "yellow"), ("T", "magenta"),
            ("S", "green"), ("Z", "red"), ("J", "blue"), ("L", "white")
        ]);
        let color = colors[name].unwrap().to_string();
        let x = (WIDTH - shape[0].len()) / 2;
        let y = 0;
        Piece { shape, x, y, name: name.to_string(), color }
    }

    fn spawn_piece(&mut self) {
        if let Some(next) = self.next_piece.take() {
            self.current_piece = Some(next);
        } else {
            self.current_piece = Some(self.new_piece());
        }
        self.next_piece = Some(self.new_piece());
        if let Some(piece) = &self.current_piece {
            if !self.valid_position(&piece.shape, piece.x, piece.y) {
                self.game_over = true;
            }
        }
    }

    fn valid_position(&self, shape: &Shape, offset_x: usize, offset_y: usize) -> bool {
        for (y, row) in shape.iter().enumerate() {
            for (x, &cell) in row.iter().enumerate() {
                if cell == 1 {
                    let new_x = offset_x + x;
                    let new_y = offset_y + y;
                    if new_x >= WIDTH || new_y >= HEIGHT {
                        return false;
                    }
                    if new_y < HEIGHT && self.board[new_y][new_x] != 0 {
                        return false;
                    }
                }
            }
        }
        true
    }

    fn lock_piece(&mut self) {
        if let Some(piece) = &self.current_piece {
            for (y, row) in piece.shape.iter().enumerate() {
                for (x, &cell) in row.iter().enumerate() {
                    if cell == 1 {
                        let board_y = piece.y + y;
                        let board_x = piece.x + x;
                        if board_y < HEIGHT {
                            self.board[board_y][board_x] = 1; // цвет будет обрабатываться при отрисовке
                        } else {
                            self.game_over = true;
                            return;
                        }
                    }
                }
            }
            self.clear_lines();
            self.spawn_piece();
        }
    }

    fn clear_lines(&mut self) {
        let mut lines_cleared = 0;
        let mut new_board = Vec::new();
        for row in &self.board {
            if row.iter().any(|&c| c == 0) {
                new_board.push(row.clone());
            } else {
                lines_cleared += 1;
            }
        }
        for _ in 0..lines_cleared {
            new_board.insert(0, vec![0; WIDTH]);
        }
        self.board = new_board;
        if lines_cleared > 0 {
            self.lines_cleared += lines_cleared as u32;
            let scores = [0, 100, 300, 500, 800];
            let add_score = scores[lines_cleared.min(4)] * self.level;
            self.score += add_score;
            self.level = self.lines_cleared / 10 + 1;
            self.fall_interval = (1.0 - (self.level - 1) as f64 * 0.07).max(0.1);
            if self.score > self.high_score {
                self.high_score = self.score;
                self.save_high_score();
            }
        }
    }

    fn move_piece(&mut self, dx: i32, dy: i32) -> bool {
        if self.game_over || self.paused { return false; }
        if let Some(piece) = &mut self.current_piece {
            let new_x = piece.x as i32 + dx;
            let new_y = piece.y as i32 + dy;
            if new_x >= 0 && new_y >= 0 {
                if self.valid_position(&piece.shape, new_x as usize, new_y as usize) {
                    piece.x = new_x as usize;
                    piece.y = new_y as usize;
                    return true;
                }
            }
        }
        false
    }

    fn rotate_piece(&mut self) {
        if self.game_over || self.paused { return; }
        if let Some(piece) = &mut self.current_piece {
            let rotated = piece.rotate();
            if self.valid_position(&rotated, piece.x, piece.y) {
                piece.shape = rotated;
            }
        }
    }

    fn hard_drop(&mut self) {
        if self.game_over || self.paused { return; }
        while self.move_piece(0, 1) {}
        self.lock_piece();
    }

    fn update(&mut self, dt: f64) {
        if self.game_over || self.paused { return; }
        self.fall_time += dt;
        if self.fall_time >= self.fall_interval {
            self.fall_time = 0.0;
            if !self.move_piece(0, 1) {
                self.lock_piece();
            }
        }
    }

    fn draw(&self, stdout: &mut termion::raw::RawTerminal<std::io::Stdout>) {
        write!(stdout, "{}", termion::clear::All).unwrap();
        write!(stdout, "{}", termion::cursor::Goto(1, 1)).unwrap();
        println!("Тетрис (с физикой)   Счёт: {}   Уровень: {}   Рекорд: {}", self.score, self.level, self.high_score);
        println!("┌{}┐", "─".repeat(WIDTH * 2 + 1));
        for y in 0..HEIGHT {
            print!("│");
            for x in 0..WIDTH {
                let cell = self.board[y][x];
                let mut drawn = false;
                if let Some(piece) = &self.current_piece {
                    if !self.game_over {
                        for (py, row) in piece.shape.iter().enumerate() {
                            for (px, &val) in row.iter().enumerate() {
                                if val == 1 && piece.y + py == y && piece.x + px == x {
                                    let color = match piece.color.as_str() {
                                        "cyan" => "█".cyan(),
                                        "yellow" => "█".yellow(),
                                        "magenta" => "█".magenta(),
                                        "green" => "█".green(),
                                        "red" => "█".red(),
                                        "blue" => "█".blue(),
                                        _ => "█".white(),
                                    };
                                    print!("{}", color);
                                    drawn = true;
                                    break;
                                }
                            }
                            if drawn { break; }
                        }
                    }
                }
                if !drawn {
                    if cell != 0 {
                        print!("{}", "█".white());
                    } else {
                        print!("  ");
                    }
                }
            }
            println!("│");
        }
        println!("└{}┘", "─".repeat(WIDTH * 2 + 1));
        // следующая фигура
        if let Some(next) = &self.next_piece {
            println!("Следующая:");
            for row in &next.shape {
                print!("  ");
                for &cell in row {
                    if cell == 1 {
                        let color = match next.color.as_str() {
                            "cyan" => "██".cyan(),
                            "yellow" => "██".yellow(),
                            "magenta" => "██".magenta(),
                            "green" => "██".green(),
                            "red" => "██".red(),
                            "blue" => "██".blue(),
                            _ => "██".white(),
                        };
                        print!("{}", color);
                    } else {
                        print!("  ");
                    }
                }
                println!();
            }
        }
        if self.game_over {
            println!("{}", "ИГРА ОКОНЧЕНА! Нажмите R для рестарта".red());
        } else if self.paused {
            println!("{}", "ПАУЗА".yellow());
        }
        stdout.flush().unwrap();
    }

    fn run(&mut self) {
        let mut stdout = io::stdout().into_raw_mode().unwrap();
        let stdin = io::stdin();
        let mut keys = stdin.keys();

        write!(stdout, "{}", termion::clear::All).unwrap();
        write!(stdout, "{}", termion::cursor::Goto(1, 1)).unwrap();
        println!("Тетрис (с физикой)");
        println!("Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход");
        println!("Нажмите любую клавишу для начала...");
        stdout.flush().unwrap();
        keys.next(); // ждём клавишу

        let mut last_update = Instant::now();
        while self.running {
            let now = Instant::now();
            let dt = now - last_update;
            last_update = now;

            // Ввод
            if let Some(Ok(key)) = keys.next() {
                match key {
                    termion::event::Key::Char('q') | termion::event::Key::Char('Q') => self.running = false,
                    termion::event::Key::Char('p') | termion::event::Key::Char('P') => self.paused = !self.paused,
                    termion::event::Key::Char('r') | termion::event::Key::Char('R') if self.game_over => {
                        *self = Tetris::new();
                        self.running = true;
                    }
                    termion::event::Key::Left | termion::event::Key::Char('a') => { self.move_piece(-1, 0); }
                    termion::event::Key::Right | termion::event::Key::Char('d') => { self.move_piece(1, 0); }
                    termion::event::Key::Up | termion::event::Key::Char('w') => { self.rotate_piece(); }
                    termion::event::Key::Down | termion::event::Key::Char('s') => { self.move_piece(0, 1); }
                    termion::event::Key::Char(' ') => { self.hard_drop(); }
                    _ => {}
                }
            }

            self.update(dt.as_secs_f64());
            self.draw(&mut stdout);
            std::thread::sleep(std::time::Duration::from_millis(30));
        }
    }
}

fn main() {
    let mut game = Tetris::new();
    game.run();
}
