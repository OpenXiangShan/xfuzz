import csv
import os
import sys

import xlsxwriter


def read_csv(filename):
    data = []
    with open(filename, 'r') as csvfile:
        reader = csv.reader(csvfile)
        for row in reader:
            data.append(row)
    return data

def calculate_metrics(data):
    img_sizes = [int(row[0]) for row in data]
    mem_reqs = [int(row[1]) for row in data]
    mem_reqs_in_range = [int(row[2]) for row in data]

    img_fractions = [(in_range / img_size) if img_size != 0 else 1 for img_size, in_range in zip(img_sizes, mem_reqs_in_range)]
    out_of_range_accesses = [mem_req - in_range for mem_req, in_range in zip(mem_reqs, mem_reqs_in_range)]

    return img_fractions, out_of_range_accesses

def calculate_intervals(metrics, num_intervals, interval_index):
    intervals = dict()
    for i in range(num_intervals):
        lower, upper = interval_index(i), interval_index(i + 1)
        uppers_s = f"{upper:.2f}" if i < num_intervals - 1 else "inf"
        interval = f"[{lower:.2f}, {uppers_s})"
        intervals[interval] = 0
        for value in metrics:
            if lower <= value and (value < upper or i == num_intervals - 1):
                intervals[interval] += 1
    return intervals

def average_metrics(metrics_list):
    num_files = len(metrics_list)
    avg_metrics = [sum(metrics) / num_files for metrics in zip(*metrics_list)]
    return avg_metrics

def main():
    data = dict()
    aver_img_frac, aver_oor = 0, 0
    input_files = sys.argv[1:]
    prefix = len(os.path.commonprefix(input_files))
    suffix = len(os.path.commonprefix([f[::-1] for f in input_files]))

    for file in input_files:
        img_fractions, out_of_range_accesses = calculate_metrics(read_csv(file))

        print(f"File: {file}")

        average_img_fraction = sum(img_fractions) / len(img_fractions)
        print(f"Image Fractions: {average_img_fraction}")
        aver_img_frac += average_img_fraction

        average_oor = sum(out_of_range_accesses) / len(out_of_range_accesses)
        print(f"Out-of-Range Accesses: {average_oor}")
        aver_oor += average_oor

        img_intervals = calculate_intervals(img_fractions, num_intervals=20, interval_index=lambda i: i * 0.05)
        out_of_range_intervals = calculate_intervals(out_of_range_accesses, num_intervals=9, interval_index=lambda i: 2 ** i)
        data[file[prefix:-suffix]] = {"accessed": img_intervals, "out-of-range": out_of_range_intervals}

        print()

    print(f"Average Image Fractions: {aver_img_frac / len(input_files)}")
    print(f"Average Out-of-Range Accesses: {aver_oor / len(input_files)}")

    columns = sorted(set(sum([list(d.keys()) for d in data.values()], [])))
    def sort_interval(s):
        return float(s.strip('[)').split(', ')[0])
    rows = [sorted(set(sum([list(d[c].keys()) for d in data.values()], [])), key=sort_interval) for c in columns]
    rows = dict(zip(columns, rows))

    workbook = xlsxwriter.Workbook('data.xlsx')
    for c in columns:
        worksheet = workbook.add_worksheet(c)
        for i, f in enumerate(data.keys()):
            worksheet.write(0, 1 + i, f)
            for j, r in enumerate(rows[c]):
                worksheet.write(1 + j, 0, r)
                worksheet.write(1 + j, 1 + i, data[f][c][r])
    workbook.close()

if __name__ == "__main__":
    main()
