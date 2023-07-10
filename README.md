# xfuzz

This project aims at fuzzing general-purpose hardware designs with software fuzzers.
For now, we are using the [LibAFL](https://github.com/AFLplusplus/LibAFL) as the underlying fuzzing framework.

## Usage

The [Makefile](Makefile) provides some simple commands to build the target `libfuzzer.a`.

- `make init` to initialize the project
- `make clean` to clean up the project
- `make build` to build the dependencies
- `make rebuild` to clean up and build the project

## Integrating Hardware Designs

This repository is not a self-running repository.
The created static library `libfuzzer.a` is expected be linked into a simulation runner.
For example, it could be passed to [Verilator](https://github.com/verilator/verilator) as an external library for linking.
The required interfaces between Rust and C/C++ modules are listed exclusively at [the harness file](src/harness.rs).

We are going to upgrade the [DiffTest](https://github.com/OpenXiangShan/difftest) environment to support these interfaces in the near future.

Once you build the simulation executable, [xfuzz](xfuzz) provides some Python scripts to run the fuzzer and parse the outputs.

## License

This project is licensed under [the Mulan Permissive Software License, Version 2](LICENSE).
