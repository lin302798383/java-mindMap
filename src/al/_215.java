package al;

public class _215 {
    public static void main(String[] args) {
        int[] nums = {3,2,1,5,6,4};
        System.out.println(nums[findKthLargest(nums, 2)]);
    }

    public static int findKthLargest(int[] nums, int k) {
        int idx;
        int left = 0;
        int right = nums.length - 1;
        int targetIdx = right - k + 1;
        while ((idx = quickSortImpl(nums, left, right)) != targetIdx) {
            if (idx > targetIdx) {
                right = idx - 1;
            } else {
                left = idx + 1;
            }
        }
        return nums[idx];
    }

    /**
     * 快排，返回该次排序后基准元素的位置
     */
    public static int quickSortImpl(int[] nums, int left, int right) {
        int baseNum = nums[left];
        int baseIdx=left;
        while (left < right) {
            while (left < right && nums[right] >= baseNum) {
                right--;
            }
            while (left < right && nums[left] <= baseNum) {
                left++;
            }
            swap(nums, left, right);
        }
        swap(nums, baseIdx, left);
        return left;
    }

    public static void swap(int[] nums, int i, int j) {
        int temp = nums[i];
        nums[i] = nums[j];
        nums[j] = temp;
        System.out.println(i + " " + j);
    }
}
