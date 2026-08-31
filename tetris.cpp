// tetris.cpp
#include <iostream>
#include <vector>
#include <map>
#include <random>
#include <chrono>
#include <thread>
#include <cstdlib>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/select.h>
#include <fstream>
#include <json/json.h> // using jsoncpp

using namespace std;

const int WIDTH = 10;
const int HEIGHT = 20;

map<string, vector<vector<int>>> SHAPES = {
    {"I", {{1,1,1,1}}},
    {"O", {{1,1},{1,1}}},
    {"T", {{0,1,0},{1,1,1}}},
    {"S", {{0,1,1},{1,1,0}}},
    {"Z", {{1,1,0},{0,1,1}}},
    {"J", {{1,0,0},{1,1,1}}},
    {"L", {{0,0,1},{1,1,1}}}
};
map<string, string> COLORS = {
    {"I","cyan"}, {"O","yellow"}, {"T","magenta"},
    {"S","green"}, {"Z","red"}, {"J","blue"}, {"L","white"}
};

struct Piece {
    vector<vector<int>> shape;
    int x, y;
    string name;
    string color;
};

class Tetris {
private:
    vector<vector<int>> board;
    int score, level, linesCleared, highScore;
    bool gameOver, paused, running;
    Piece* currentPiece;
    Piece* nextPiece;
    double fallInterval, fallTime;
    mt19937 rng;
    string inputBuffer;

public:
    Tetris() : score(0), level(1), linesCleared(0), gameOver(false), paused(false), running(true),
               fallInterval(1.0), fallTime(0.0), currentPiece(nullptr), nextPiece(nullptr) {
        board.resize(HEIGHT, vector<int>(WIDTH, 0));
        highScore = loadHighScore();
        rng.seed(chrono::steady_clock::now().time_since_epoch().count());
        nextPiece = newPiece();
        spawnPiece();
        setupTerminal();
    }

    ~Tetris() {
        delete currentPiece;
        delete nextPiece;
        restoreTerminal();
    }

    int loadHighScore() {
        ifstream ifs("tetris_score.json");
        if (!ifs) return 0;
        Json::Value root;
        ifs >> root;
        return root.get("high_score", 0).asInt();
    }

    void saveHighScore() {
        Json::Value root;
        root["high_score"] = highScore;
        ofstream ofs("tetris_score.json");
        ofs << root.toStyledString();
    }

    Piece* newPiece() {
        vector<string> names = {"I","O","T","S","Z","J","L"};
        uniform_int_distribution<int> dist(0, names.size()-1);
        string name = names[dist(rng)];
        auto shape = SHAPES[name];
        int x = (WIDTH - shape[0].size()) / 2;
        Piece* p = new Piece;
        p->shape = shape;
        p->x = x;
        p->y = 0;
        p->name = name;
        p->color = COLORS[name];
        return p;
    }

    void spawnPiece() {
        if (nextPiece) {
            currentPiece = nextPiece;
        } else {
            currentPiece = newPiece();
        }
        nextPiece = newPiece();
        if (!validPosition(currentPiece->shape, currentPiece->x, currentPiece->y)) {
            gameOver = true;
        }
    }

    bool validPosition(const vector<vector<int>>& shape, int offX, int offY) {
        for (int r = 0; r < shape.size(); r++) {
            for (int c = 0; c < shape[r].size(); c++) {
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

    void lockPiece() {
        if (!currentPiece) return;
        for (int r = 0; r < currentPiece->shape.size(); r++) {
            for (int c = 0; c < currentPiece->shape[r].size(); c++) {
                if (currentPiece->shape[r][c] == 1) {
                    int boardY = currentPiece->y + r;
                    int boardX = currentPiece->x + c;
                    if (boardY < 0) { gameOver = true; return; }
                    board[boardY][boardX] = 1;
                }
            }
        }
        clearLines();
        spawnPiece();
    }

    void clearLines() {
        int cleared = 0;
        vector<vector<int>> newBoard;
        for (int y = 0; y < HEIGHT; y++) {
            bool full = true;
            for (int x = 0; x < WIDTH; x++) {
                if (board[y][x] == 0) { full = false; break; }
            }
            if (full) {
                cleared++;
            } else {
                newBoard.push_back(board[y]);
            }
        }
        for (int i = 0; i < cleared; i++) {
            newBoard.insert(newBoard.begin(), vector<int>(WIDTH, 0));
        }
        board = newBoard;
        if (cleared > 0) {
            linesCleared += cleared;
            int scores[] = {0,100,300,500,800};
            score += scores[min(cleared,4)] * level;
            level = linesCleared / 10 + 1;
            fallInterval = max(0.1, 1.0 - (level-1) * 0.07);
            if (score > highScore) { highScore = score; saveHighScore(); }
        }
    }

    vector<vector<int>> rotateShape(const vector<vector<int>>& shape) {
        int rows = shape.size(), cols = shape[0].size();
        vector<vector<int>> rotated(cols, vector<int>(rows, 0));
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                rotated[j][rows-1-i] = shape[i][j];
            }
        }
        return rotated;
    }

    bool movePiece(int dx, int dy) {
        if (gameOver || paused || !currentPiece) return false;
        int newX = currentPiece->x + dx;
        int newY = currentPiece->y + dy;
        if (validPosition(currentPiece->shape, newX, newY)) {
            currentPiece->x = newX;
            currentPiece->y = newY;
            return true;
        }
        return false;
    }

    void rotatePiece() {
        if (gameOver || paused || !currentPiece) return;
        auto rotated = rotateShape(currentPiece->shape);
        if (validPosition(rotated, currentPiece->x, currentPiece->y)) {
            currentPiece->shape = rotated;
        }
    }

    void hardDrop() {
        if (gameOver || paused || !currentPiece) return;
        while (movePiece(0, 1)) {}
        lockPiece();
    }

    void update(double dt) {
        if (gameOver || paused) return;
        fallTime += dt;
        if (fallTime >= fallInterval) {
            fallTime = 0;
            if (!movePiece(0, 1)) {
                lockPiece();
            }
        }
    }

    string colorize(const string& str, const string& color) {
        if (color == "cyan") return "\033[36m" + str + "\033[0m";
        if (color == "yellow") return "\033[33m" + str + "\033[0m";
        if (color == "magenta") return "\033[35m" + str + "\033[0m";
        if (color == "green") return "\033[32m" + str + "\033[0m";
        if (color == "red") return "\033[31m" + str + "\033[0m";
        if (color == "blue") return "\033[34m" + str + "\033[0m";
        return "\033[37m" + str + "\033[0m";
    }

    void draw() {
        system("clear");
        cout << "\033[36mТетрис (с физикой)   Счёт: " << score << "   Уровень: " << level << "   Рекорд: " << highScore << "\033[0m" << endl;
        cout << "┌" << string(WIDTH*2+1, '─') << "┐" << endl;
        for (int y = 0; y < HEIGHT; y++) {
            cout << "│";
            for (int x = 0; x < WIDTH; x++) {
                bool drawn = false;
                if (currentPiece && !gameOver) {
                    for (int r = 0; r < currentPiece->shape.size() && !drawn; r++) {
                        for (int c = 0; c < currentPiece->shape[r].size() && !drawn; c++) {
                            if (currentPiece->shape[r][c] == 1 && currentPiece->y + r == y && currentPiece->x + c == x) {
                                cout << colorize("██", currentPiece->color);
                                drawn = true;
                            }
                        }
                    }
                }
                if (!drawn) {
                    if (board[y][x] != 0) {
                        cout << "\033[37m██\033[0m";
                    } else {
                        cout << "  ";
                    }
                }
            }
            cout << "│" << endl;
        }
        cout << "└" << string(WIDTH*2+1, '─') << "┘" << endl;
        if (nextPiece) {
            cout << "Следующая:" << endl;
            for (auto& row : nextPiece->shape) {
                cout << "  ";
                for (int cell : row) {
                    if (cell == 1) {
                        cout << colorize("██", nextPiece->color);
                    } else {
                        cout << "  ";
                    }
                }
                cout << endl;
            }
        }
        if (gameOver) cout << "\033[31mИГРА ОКОНЧЕНА! Нажмите R для рестарта\033[0m" << endl;
        else if (paused) cout << "\033[33mПАУЗА\033[0m" << endl;
    }

    void setupTerminal() {
        // Настраиваем неблокирующий ввод
        struct termios term;
        tcgetattr(STDIN_FILENO, &term);
        term.c_lflag &= ~(ICANON | ECHO);
        tcsetattr(STDIN_FILENO, TCSANOW, &term);
        fcntl(STDIN_FILENO, F_SETFL, O_NONBLOCK);
    }

    void restoreTerminal() {
        struct termios term;
        tcgetattr(STDIN_FILENO, &term);
        term.c_lflag |= (ICANON | ECHO);
        tcsetattr(STDIN_FILENO, TCSANOW, &term);
        fcntl(STDIN_FILENO, F_SETFL, 0);
    }

    char getChar() {
        char ch;
        if (read(STDIN_FILENO, &ch, 1) > 0) {
            return ch;
        }
        return 0;
    }

    void run() {
        cout << "\033[36mТетрис (с физикой)\033[0m" << endl;
        cout << "Управление: ← →, ↑ вращение, ↓ ускорение, Space - мгновенное падение, P - пауза, Q - выход" << endl;
        cout << "Нажмите Enter для начала..." << endl;
        cin.get();

        auto lastTime = chrono::steady_clock::now();
        while (running) {
            auto now = chrono::steady_clock::now();
            double dt = chrono::duration<double>(now - lastTime).count();
            lastTime = now;

            // Обработка ввода
            char ch = getChar();
            if (ch) {
                if (ch == 'q' || ch == 'Q') { running = false; break; }
                if (ch == 'p' || ch == 'P') { paused = !paused; }
                if ((ch == 'r' || ch == 'R') && gameOver) {
                    *this = Tetris();
                }
                if (!paused && !gameOver) {
                    switch (ch) {
                        case 'a': movePiece(-1, 0); break;
                        case 'd': movePiece(1, 0); break;
                        case 'w': rotatePiece(); break;
                        case 's': movePiece(0, 1); break;
                        case ' ': hardDrop(); break;
                        case 27: { // Escape sequences for arrows
                            char c1, c2;
                            if (read(STDIN_FILENO, &c1, 1) > 0 && read(STDIN_FILENO, &c2, 1) > 0) {
                                if (c1 == '[') {
                                    if (c2 == 'A') rotatePiece();
                                    else if (c2 == 'B') movePiece(0, 1);
                                    else if (c2 == 'C') movePiece(1, 0);
                                    else if (c2 == 'D') movePiece(-1, 0);
                                }
                            }
                            break;
                        }
                    }
                }
            }

            update(dt);
            draw();
            this_thread::sleep_for(chrono::milliseconds(30));
        }
    }
};

int main() {
    Tetris game;
    game.run();
    return 0;
}
