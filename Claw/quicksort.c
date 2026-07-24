#include <stdio.h>

/*
 * 快速排序（Lomuto 分区方案，原地排序，平均 O(n log n)）
 * 选取区间最右元素为 pivot，将 <= pivot 的元素聚到左侧，
 * 最后把 pivot 交换到分界点。
 */
static int partition(int *a, int lo, int hi)
{
	int pivot = a[hi];
	int i = lo;            /* i 指向「<= pivot 区域」的下一个空位 */
	int j, tmp;

	for (j = lo; j < hi; j++) {
		if (a[j] <= pivot) {
			tmp = a[i]; a[i] = a[j]; a[j] = tmp;
			i++;
		}
	}
	tmp = a[i]; a[i] = a[hi]; a[hi] = tmp;  /* pivot 就位 */
	return i;
}

static void quicksort(int *a, int lo, int hi)
{
	if (lo < hi) {
		int p = partition(a, lo, hi);
		quicksort(a, lo, p - 1);
		quicksort(a, p + 1, hi);
	}
}

int main(void)
{
	int a[] = { 33, 12, 55, 2, 18, 90, 7, 42, 3, 61 };
	int n = sizeof(a) / sizeof(a[0]);
	int i;

	quicksort(a, 0, n - 1);

	for (i = 0; i < n; i++)
		printf("%d ", a[i]);
	printf("\n");
	return 0;
}
