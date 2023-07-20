/**
 * Copyright (c) 2023 Institute of Computing Technology, Chinese Academy of Sciences
 * xfuzz is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

mod coverage;
mod fuzzer;
mod harness;
mod monitor;

use clap::Parser;

#[derive(Parser, Default, Debug)]
struct Arguments {
    // Fuzzer options
    #[clap(default_value_t = false, short, long)]
    fuzzing: bool,
    #[clap(default_value_t = String::from("instr-imm"), short, long)]
    coverage: String,
    #[clap(default_value_t = false, short, long)]
    verbose: bool,
    #[clap(long)]
    max_iters: Option<u64>,
    #[clap(long)]
    max_runs: Option<u64>,
    #[clap(default_value_t = false, long)]
    random_input: bool,
    #[clap(default_value_t = String::from("./corpus"), long)]
    corpus_input: String,
    #[clap(long)]
    corpus_output: Option<String>,
    // Run options
    #[clap(default_value_t = 1, long)]
    repeat: usize,
    #[clap(default_value_t = false, long)]
    auto_exit: bool,
    extra_args: Vec<String>,
}

#[no_mangle]
fn main() -> i32 {
    let args = Arguments::parse();

    let mut workloads: Vec<String> = Vec::new();
    let mut emu_args: Vec<String> = Vec::new();

    let mut is_emu = false;
    for arg in args.extra_args {
        if arg.starts_with("-") {
            is_emu = true;
        }

        if is_emu {
            emu_args.push(arg);
        } else {
            workloads.push(arg);
        }
    }

    harness::set_sim_env(args.coverage, args.verbose, args.max_runs, emu_args);

    let mut has_failed = 0;
    if workloads.len() > 0 {
        for _ in 0..args.repeat {
            let ret = harness::sim_run_multiple(&workloads, args.auto_exit);
            if ret != 0 {
                has_failed = 1;
                if args.auto_exit {
                    return ret;
                }
            }
        }
        coverage::cover_display();
    }

    if args.fuzzing {
        let corpus_input = if args.corpus_input == "random" {
            None
        } else {
            Some(args.corpus_input)
        };
        fuzzer::run_fuzzer(
            args.random_input,
            args.max_iters,
            corpus_input,
            args.corpus_output,
        );
    }

    return has_failed;
}
