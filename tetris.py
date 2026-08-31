#!/usr/bin/env python3
# tetris.py
import random
import sys
import time
import os
import json
from threading import Thread
from collections import deque
try:
    from colorama import init, Fore, Back, Style
    init(autoreset=True)
except ImportError:
    # заглушки для цветов, если colorama не установлен
    class Fore:
        BLACK = RED = GREEN = YELLOW = BLUE = MAGENTA = CYAN = WHITE = RESET = ''
    class Back:
        BLACK = RED = GREEN = YELLOW = BLUE = MAGENTA = CYAN = WHITE = RESET = ''
    class Style:
        BRIGHT = DIM = NORMAL = RESET_ALL = ''

WIDTH = 10
HEIGHT = 20
BLOCK_SIZE = 2  # для отображения

# Фигуры (матрицы)
SHAPES = {
    'I': [[1,1,1,1]],
    'O': [[1,1],[1,1]],
    'T': [[0,1,0],[1,1,1]],
    'S': [[0,1,1],[1,1,0]],
    'Z': [[1,1,0],[0,1,1]],
    'J': [[1,0,0],[1,1,1]],
    'L': [[0,0,1],[1,1,1]]
}
COLORS = {
    'I': Fore.CYAN,
    'O': Fore.YELLOW,
    'T': Fore.MAGENTA,
    'S': Fore.GREEN,
    'Z': Fore.RED,
    'J': Fore.BLUE,
    'L': Fore.WHITE
}

class Tetris:
    def __init__(self):
        self.width = WIDTH
        self.height = HEIGHT
        self.board = [[0 for _ in range(self.width)] for _ in range(self.height)]
        self.score = 0
        self.level = 1
        self.lines_cleared = 0
        self.game_over = False
        self.paused = False
        self.current_piece = None
        self.next_piece = None
        self.fall_time = 0
        self.fall_interval = 1.0  # начальная секунда
        self.hold_piece = None
        self.can_hold = True
        self.high_score = self.load_high_score()
        self.running = True
        self.drop_physics = False  # флаг мгновенного падения

    def load_high_score(self):
        try:
            with open('tetris_score.json', 'r') as f:
                return json.load(f).get('high_score', 0)
        except:
            return 0

    def save_high_score(self):
        with open('tetris_score.json', 'w') as f:
            json.dump({'high_score': self.high_score}, f)

    def new_piece(self):
        shape_name = random.choice(list(SHAPES.keys()))
        shape = [row[:] for row in SHAPES[shape_name]]
        color = COLORS[shape_name]
        # Позиция появления (центр)
        x = (self.width - len(shape[0])) // 2
        y = 0
        return {'shape': shape, 'x': x, 'y': y, 'name': shape_name, 'color': color}

    def rotate_shape(self, shape):
        # Поворот на 90 градусов по часовой стрелке
        return [list(row) for row in zip(*shape[::-1])]

    def valid_position(self, shape, offset_x, offset_y):
        for y, row in enumerate(shape):
            for x, cell in enumerate(row):
                if cell:
                    new_x = offset_x + x
                    new_y = offset_y + y
                    if new_x < 0 or new_x >= self.width or new_y >= self.height:
                        return False
                    if new_y < 0:
                        continue
                    if self.board[new_y][new_x] != 0:
                        return False
        return True

    def lock_piece(self):
        shape = self.current_piece['shape']
        x = self.current_piece['x']
        y = self.current_piece['y']
        color = self.current_piece['color']
        for row_idx, row in enumerate(shape):
            for col_idx, cell in enumerate(row):
                if cell:
                    board_y = y + row_idx
                    board_x = x + col_idx
                    if board_y < 0:
                        self.game_over = True
                        return
                    self.board[board_y][board_x] = color
        self.clear_lines()
        self.spawn_piece()

    def clear_lines(self):
        lines_cleared = 0
        new_board = [row for row in self.board if any(cell == 0 for cell in row)]
        lines_cleared = self.height - len(new_board)
        for _ in range(lines_cleared):
            new_board.insert(0, [0 for _ in range(self.width)])
        self.board = new_board
        if lines_cleared > 0:
            self.lines_cleared += lines_cleared
            # очки по классике
            scores = [0, 100, 300, 500, 800]
            self.score += scores[lines_cleared] * self.level
            self.level = self.lines_cleared // 10 + 1
            self.fall_interval = max(0.1, 1.0 - (self.level-1) * 0.07)
            if self.score > self.high_score:
                self.high_score = self.score
                self.save_high_score()

    def spawn_piece(self):
        if self.next_piece is None:
            self.next_piece = self.new_piece()
        self.current_piece = self.next_piece
        self.next_piece = self.new_piece()
        self.can_hold = True
        if not self.valid_position(self.current_piece['shape'], self.current_piece['x'], self.current_piece['y']):
            self.game_over = True

    def move(self, dx, dy):
        if self.game_over or self.paused:
            return
        shape = self.current_piece['shape']
        new_x = self.current_piece['x'] + dx
        new_y = self.current_piece['y'] + dy
        if self.valid_position(shape, new_x, new_y):
            self.current_piece['x'] = new_x
            self.current_piece['y'] = new_y
            return True
        return False

    def rotate(self):
        if self.game_over or self.paused:
            return
        shape = self.current_piece['shape']
        rotated = self.rotate_shape(shape)
        if self.valid_position(rotated, self.current_piece['x'], self.current_piece['y']):
            self.current_piece['shape'] = rotated

    def hard_drop(self):
        if self.game_over or self.paused:
            return
        while self.valid_position(self.current_piece['shape'], self.current_piece['x'], self.current_piece['y'] + 1):
            self.current_piece['y'] += 1
        self.lock_piece()

    def update(self, dt):
        if self.game_over or self.paused:
            return
        self.fall_time += dt
        if self.fall_time >= self.fall_interval:
            self.fall_time = 0
            if not self.move(0, 1):
                self.lock_piece()

    def draw(self):
        os.system('cls' if os.name == 'nt' else 'clear')
        # Рисуем игровое поле
        print("Тетрис (с физикой)   Счёт: {}   Уровень: {}   Рекорд: {}".format(self.score, self.level, self.high_score))
        print("┌" + "─" * (self.width * 2 + 1) + "┐")
        for y in range(self.height):
            line = "│"
            for x in range(self.width):
                cell = self.board[y][x]
                if cell != 0:
                    line += cell + "█" + Fore.RESET
                else:
                    # Проверяем, есть ли текущая фигура в этой позиции
                    piece_drawn = False
                    if self.current_piece and not self.game_over:
                        px = self.current_piece['x']
                        py = self.current_piece['y']
                        shape = self.current_piece['shape']
                        for row_idx, row in enumerate(shape):
                            for col_idx, val in enumerate(row):
                                if val and py + row_idx == y and px + col_idx == x:
                                    line += self.current_piece['color'] + "██" + Fore.RESET
                                    piece_drawn = True
                                    break
                            if piece_drawn:
                                break
                    if not piece_drawn:
                        line += "  "
            line += "│"
            print(line)
        print("└" + "─" * (self.width * 2 + 1) + "┘")
        # Показываем следующую фигуру
        if self.next_piece:
            print("Следующая:")
            for row in self.next_piece['shape']:
                line = "  "
                for cell in row:
                    line += (self.next_piece['color'] + "██" + Fore.RESET) if cell else "  "
                print(line)
        if self.game_over:
            print(Fore.RED + "ИГРА ОКОНЧЕНА! Нажмите R для рестарта" + Style.RESET_ALL)
        elif self.paused:
            print(Fore.YELLOW + "ПАУЗА" + Style.RESET_ALL)

    def run(self):
        self.next_piece = self.new_piece()
        self.spawn_piece()
        # Обработка ввода в отдельном потоке
        import threading
        import queue
        input_queue = queue.Queue()

        def get_input():
            try:
                import keyboard
                while self.running:
                    event = keyboard.read_event()
                    if event.event_type == 'down':
                        input_queue.put(event.name)
            except ImportError:
                # fallback для систем без keyboard
                import msvcrt if os.name == 'nt' else select, sys, tty, termios
                if os.name == 'nt':
                    while self.running:
                        if msvcrt.kbhit():
                            ch = msvcrt.getch().decode('utf-8', errors='ignore')
                            input_queue.put(ch)
                        time.sleep(0.05)
                else:
                    fd = sys.stdin.fileno()
                    old = termios.tcgetattr(fd)
                    try:
                        tty.setraw(fd)
                        while self.running:
                            if select.select([sys.stdin], [], [], 0.05)[0]:
                                ch = sys.stdin.read(1)
                                input_queue.put(ch)
                    finally:
                        termios.tcsetattr(fd, termios.TCSADRAIN, old)

        threading.Thread(target=get_input, daemon=True).start()

        last_time = time.time()
        while self.running and not self.game_over:
            current_time = time.time()
            dt = current_time - last_time
            last_time = current_time

            # Обработка ввода
            while not input_queue.empty():
                key = input_queue.get()
                if key == 'q' or key == 'Q':
                    self.running = False
                elif key == 'p' or key == 'P':
                    self.paused = not self.paused
                elif key == 'r' or key == 'R' and self.game_over:
                    self.__init__()
                elif not self.paused and not self.game_over:
                    if key == 'left' or key == 'a':
                        self.move(-1, 0)
                    elif key == 'right' or key == 'd':
                        self.move(1, 0)
                    elif key == 'up' or key == 'w':
                        self.rotate()
                    elif key == 'down' or key == 's':
                        self.move(0, 1)
                    elif key == 'space':
                        self.hard_drop()

            self.update(dt)
            self.draw()
            time.sleep(0.03)

        if self.game_over:
            self.draw()
            print("Игра окончена. Нажмите R для рестарта или Q для выхода.")
            while True:
                if not input_queue.empty():
                    key = input_queue.get()
                    if key == 'r' or key == 'R':
                        self.__init__()
                        self.run()
                    elif key == 'q' or key == 'Q':
                        break
                time.sleep(0.1)

if __name__ == "__main__":
    game = Tetris()
    game.run()
