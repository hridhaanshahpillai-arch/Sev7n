# C-style Seven

Seven also understands code written the C# or C++ way, so you can write in whichever style you know. Both styles work in the same file, in Sev7n and in Emerald.

## Variables and functions

```csharp
int score = 0;
float speed = 2.5f;
string name = "Pip";
bool alive = true;
int lives;                  // starts at 0 ("" for string, false for bool)

int add(int a, int b) {
    return a + b;
}

void jump() {
    vy = -3;
}
```

The types are labels: Seven doesn't check them, so `int x = "hi"` is allowed. `var`, `auto`, `const`, `static`, `public` and `private` are fine too.

## Loops and ifs

```csharp
for (int i = 0; i < 10; i++) {
    if (i % 2 == 0) continue;
    print(i);
}

foreach (var enemy in enemies) { }
for (auto enemy : enemies) { }

if (hp <= 0) die();         // no braces needed after a condition in brackets
while (x < 10) x++;
```

`i++`, `++i`, `i--` and `--i` all work.

## Printing

```csharp
Console.WriteLine("Hello");
Debug.Log("Unity style");
std::cout << "HP: " << hp << std::endl;
```

## Scripts as classes (Sev7n)

A C# class is a Seven behaviour. `Start`, `Update`, `LateUpdate` and `FixedUpdate` are called like Seven's `start` and `update`:

```csharp
public class Spinner : MonoBehaviour {
    public float speed = 90;

    void Update(float dt) {
        entity.transform.rotation.y += speed * dt;
    }
}
```

## Also fine

`null` and `nullptr` mean `none`. `new Thing()` means `Thing()`. Lines like `using System;`, `using namespace std;` and `#include <cmath>` are skipped. A C++-style `int main() { }` runs at the end of the file.

## Not supported

Seven isn't really C# or C++, so a few things are different:

- Numbers are all decimals: `7 / 2` is `3.5`, not `3`.
- No `struct`, `enum`, `switch`, pointers or templates.
- `i++` gives the new value, so `x = i++` is the same as `x = ++i`.
- C# library classes like `Mathf` or `List<int>` don't exist. Use Seven's own (`abs`, `sin`, `clamp`, `[1, 2, 3]`...).
