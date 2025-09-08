# xfuzz

This project aims at fuzzing general-purpose hardware designs with software fuzzers.
For now, we are using the [LibAFL](https://github.com/AFLplusplus/LibAFL) as the underlying fuzzing framework.

## Usage

This is a Rust project. To install Rust, please see [https://www.rust-lang.org/tools/install](https://www.rust-lang.org/tools/install). After the installation of Rust, please run `cargo install cargo-make` to install the cargo-make library.

The [Makefile](Makefile) provides some simple commands to build the target `libfuzzer.a`.

- `make init` to initialize the project
- `make clean` to clean up the project
- `make build` to build the dependencies
- `make rebuild` to clean up and build the project

## Example

We have a real example using the rocket-chip (DUT) and Spike (REF) [here](https://github.com/OpenXiangShan/difftest/blob/master/.github/workflows/nightly.yml#L9).
This `test-difftest-fuzzing` CI test builds the fuzzer and runs it for 10000 runs (testcases).

Run `fuzzer --help` for a full list of runtime arguments.

An example build flow with rocket-chip as the design-under-test is listed as follows:

```bash
git clone https://github.com/OpenXiangShan/xfuzz.git
cd xfuzz && make init && make build && cd ..

git clone https://github.com/OpenXiangShan/riscv-arch-test.git
cd riscv-arch-test/riscv-test-suite
make build_I -j2
rm build/*.elf build/*.txt
cd ../..

git clone https://github.com/OpenXiangShan/riscv-isa-sim.git
# Replace ROCKET_CHIP with other CPU models (NUTSHELL, XIANGSHAN) if necessary
# SANCOV=1 lets the C++ compile instrument coverage definitions into the elf
# If you are not using the LLVM instrumented coverage, remove SANCOV=1 and rebuild it after `make clean`
make -C riscv-isa-sim/difftest CPU=ROCKET_CHIP SANCOV=1 -j16

# Some shortcuts. They are not required if you understand why we set them
export SPIKE_HOME=$(pwd)/riscv-isa-sim
export XFUZZ_HOME=$(pwd)/xfuzz
export NOOP_HOME=$(pwd)/rocket-chip
export CORPUS=$(pwd)/riscv-arch-test/riscv-test-suite/build

# Here we build the rocket-chip
# For other CPU models, please refer to their README for help
git clone -b dev-difftest --single-branch https://github.com/OpenXiangShan/rocket-chip.git
cd rocket-chip && make init && make bootrom
make emu XFUZZ=1 REF=$SPIKE_HOME/difftest/build/riscv64-spike-so LLVM_COVER=1 -j16

# Run the fuzzer for 100 inputs
./build/fuzzer -f --max-runs 100 --corpus-input $CORPUS -- --max-cycles 10000
```

If you are using [NEMU](https://github.com/OpenXiangShan/NEMU) as the REF with LLVM branch coverage instrumented, you may build it
with the following commands: `make staticlib CC="clang -fsanitize-coverage=trace-pc-guard -fsanitize-coverage=pc-table -g" CXX="clang++ -fsanitize-coverage=trace-pc-guard -fsanitize-coverage=pc-table -g"`.
If you are not using LLVM branch coverage, you can simply follow the NEMU docs to build the general-purpose dynamic library `.so` file.

If you are fuzzing the [XiangShan](https://github.com/OpenXiangShan/XiangShan) RISC-V processor, for example, with LLVM branch coverage instrumented NEMU,
you can use the following command *after* you build the static NEMU library: `make emu XFUZZ=1 REF=$NEMU_HOME/build/libriscv64-nemu-interpreter.a LLVM_COVER=1 EMU_THREADS=16 WITH_CHISELDB=0 WITH_CONSTANTIN=0 EMU_TRACE=1 CONFIG=FuzzConfig`.
If you are not using LLVM branch coverage, please read the following sections carefully and try the flow by yourself.

## Integrating Hardware Designs

This repository is not a self-running repository.
The created static library `libfuzzer.a` is expected be linked into a simulation runner.
For example, it could be passed to [Verilator](https://github.com/verilator/verilator) as an external library for linking.
The required interfaces between Rust and C/C++ modules are listed exclusively at [the harness file](src/harness.rs).

We have upgraded the [DiffTest](https://github.com/OpenXiangShan/difftest) environment to support these interfaces.
Please refer to the [DiffTest README.md](https://github.com/OpenXiangShan/difftest/blob/master/README.md) for connecting your CPU design with the fuzzer.

Specifically, for the coverage guidance for fuzzing, currently both C++ branch coverage via LLVM sanitizer and FIRRTL-instrumented coverage are supported.
For C++ branch coverage, both [Spike](https://github.com/OpenXiangShan/riscv-isa-sim) and [NEMU](https://github.com/OpenXiangShan/NEMU) are supported.
For FIRRTL-instrumented coverage, please refer to the [Coverage Instrumentation for Chisel Designs section](#coverage-instrumentation-for-chisel-designs) of this README.
Each of the supported coverage metrics can exist independently and is configurable in the building steps.
The build commands are self-explained and we assume the users can understand them easily.

Once you build the simulation executable, [xfuzz](xfuzz) provides some Python scripts to run the fuzzer and parse the outputs.

## Coverage Instrumentation for Chisel Designs

### CIRCT Passes

To instrument coverage for latest Chisel designs, please use the customized FIRTOOL toolchain from [OpenXiangShan](https://github.com/OpenXiangShan/circt).

### Scala FIRRTL Transforms

**Warning (September 2025): Chisel 3 has been deprecated in DiffTest. If you are using these SFC transforms, please use an older version of DiffTest (no later than [8504ad8](https://github.com/OpenXiangShan/difftest/commit/8504ad8ddf1a3b82f407b983eae14bef34358370)).**

To instrument Chisel coverage metrics into your Chisel designs and use them as the coverage feedback for fuzzing, we provide some useful FIRRTL transforms in the `instrumentation` directory.
These transforms are mostly migrated from some other projects, including [ekiwi/rfuzz](https://github.com/ekiwi/rfuzz), [compsec-snu/difuzz-rtl](https://github.com/compsec-snu/difuzz-rtl), and [ekiwi/simulator-independent-coverage](https://github.com/ekiwi/simulator-independent-coverage).

It's worth noting current instrumentation transforms support only Chisel<=3.6.0 designs due to FIRRTL constains.
For a reference design, please refer to the [rocket-chip](https://github.com/OpenXiangShan/rocket-chip/tree/dev-difftest) project.
Generally, the requirements include: 1) adding the submodule and the Scala compilation recipe to your Chisel design (example at [here](https://github.com/OpenXiangShan/rocket-chip/blob/dev-difftest/build.sc#L87-L111)), and 2) enabling FIRRTL transforms when necessary (example at [here](https://github.com/OpenXiangShan/rocket-chip/blob/dev-difftest/generator/chisel3/ccover.patch)).

Once you have successfully instrumented your Chisel design, tell the DiffTest to include FIRRTL coverage metrics using `FIRRTL_COVER=your_choice_1,your_choice_2`.
Then, the generated coverage files (`build/generated-src/firrtl-cover.*`) will be detected and captured by DiffTest.
The Chisel/FIRRTL coverage metrics will have names as `firrtl.your_choice1` and `firrtl.your_choice2`.
Lastly, replace the default `llvm.branch` coverage feedback for fuzzing with the new ones when calling the fuzzer.

All supported Chisel/FIRRTL coverage metrics are listed in the `CoverPoint.getTransforms` function [here](instrumentation/src/xfuzz/CoverPointTransform.scala).

We sincerely thank the original authors for these FIRRTL transforms.
The rights of the source code are reserved by the original repositories and authors.
If you don't like this project integrating your code, please feel free to tell us through GitHub issues to allow us remove them.

An example to build rocket-chip with FIRRTL coverage instrumentation is as follows.
It is required to build the spike-so (without `SANCOV=1`) before `make emu`.
If you are building the spike-so with `SANCOV=1`, please add `LLVM_COVER=1` as well.

```bash
git clone -b dev-difftest --single-branch https://github.com/OpenXiangShan/rocket-chip.git
cd rocket-chip && make init && make bootrom

# Apply the patch to include command line arguments and coverage transforms
git apply generator/chisel3/ccover.patch

# Add the ccover directory. You may instead add it as a git submodule if you want to track the changes
git clone https://github.com/OpenXiangShan/xfuzz.git ccover

# Now build the fuzzer with FIRRTL_COVER instead of LLVM_COVER
# Please make sure you have successfully built the spike-so at SPIKE_HOME without SANCOV=1
# To see the full list of supported FIRRTL coverage metrics, please read this README again
make emu REF=$SPIKE_HOME/difftest/build/riscv64-spike-so XFUZZ=1 FIRRTL_COVER=mux,control,line,toggle,ready_valid -j16
```

You may refer to the [FuzzingNutShell](https://github.com/poemonsense/FuzzingNutShell) repo for a demo.

## License

This project is licensed under [the Mulan Permissive Software License, Version 2](LICENSE) except explicit specified.
All files in the `instrumentation` directory are licensed under their original licenses with rights reserved by the original repositories and authors.

This project has been adopted by researchers from academia and industry.
We appreciate their contributions for the open-source community.
Your valuable Issues and Pull Requests are always welcomed.

- PathFuzz: Broadening Fuzzing Horizons with Footprint Memory for CPUs. DAC'23. [DOI (ACM, Open Access)](https://doi.org/10.1145/3649329.3655911), [Presentation (ChinaSys)](https://drive.google.com/file/d/1SfXSfWkwMqqVNuTauUpFvfP5Q5JV2oMw/view?usp=sharing).
