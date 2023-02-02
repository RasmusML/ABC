# 🍀ABC
ABC is a tiny statically typed programming language transpiled to java source code.

### Example
```
fibonacci_of :: (n: i32) -> i32 {
  return fibonacci_helper(0, 1, n-1);
}

fibonacci_helper :: (a: i32, b: i32, n: i32) -> i32 {
  if (n == 0) { return b; }
  return fibonacci_helper(b, a+b, n-1);
}

fibonnaci_up_to :: (N: i32) -> [] i32 {
  ensure(N > 0, "expected N > 0, but is N=%d.", N);
  
  result: [] i32 = new [N];
  
  i: i32 = 1;
  while (i < result.length) {
    result[i] = fibonacci_of(i);
    i = i + 1;
  }
  
  return result;
}

Planet :: struct {
  name: string;
  mass: i64;
  radius: i32;
  habitable: bool;
}

main :: () {
  N: i16 = 6;
  sequence: [] i32 = fibonnaci_up_to(N);
  
  print("first %d fibonacci numbers:\n", N);
  i: i32 = 0;
  while (i < sequence.length) {
    print("%d: %d\n", i+1, sequence[i]);
    i = i + 1;
  }
  
  print("\n");
  
  exo1: Planet = new;
  exo1.name = "PSR B1257+12 b";
  exo1.habitable = true;
  exo1.radius = 7000;
  exo1.mass = 42000;
  
  if (exo1.habitable) {
    print("Planet \"%s\" is habitable!", exo1.name);
  }
}

/*
  first 6 fibonacci numbers:
  1: 0
  2: 1
  3: 1
  4: 2
  5: 3
  6: 5

  Planet "PSR B1257+12 b" is habitable!
*/
```
